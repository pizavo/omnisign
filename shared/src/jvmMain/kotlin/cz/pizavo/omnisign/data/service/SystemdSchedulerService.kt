package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * [OsSchedulerService] implementation for Linux that manages the daily renewal job as a
 * **systemd user timer**.
 *
 * [install] writes two unit files under `~/.config/systemd/user/`: a `oneshot` `*.service`
 * that runs `omnisign renew`, and a `*.timer` with a daily `OnCalendar` trigger and
 * `Persistent=true`. `Persistent=true` provides missed-run catch-up — a run skipped because
 * the machine was off or asleep at the scheduled time is executed once the machine is next
 * available, and multiple missed activations are coalesced into a single run.
 *
 * A log file is checked before anything is registered. The user's service manager opens it
 * itself before every run, and a run whose log file it cannot open fails without OmniSign
 * starting, so nothing would record or report that renewal stopped. [install] therefore refuses
 * a relative path and one the user cannot append to, creating the file and its missing folders
 * when it can. The paths go into the service unit escaped for systemd, and [install] refuses one
 * that a unit cannot hold.
 *
 * systemd availability is probed (`systemctl --user`) by [isAvailable] and inside [install],
 * never in the constructor, so constructing this service — for example when the Koin graph is
 * built inside a container that has no systemd — performs no I/O and cannot fail. When systemd
 * is unavailable [install] throws an [IllegalStateException] whose message explains how to
 * schedule `omnisign renew` with another scheduler (cron, supercronic, a Kubernetes
 * CronJob, …).
 *
 * @param userHome Home folder whose `.config/systemd/user` holds the unit files, the current
 *   user's unless the caller names another; tests do, so that they never touch the user's units.
 * @param systemctl The `systemctl` command this service runs; tests run a stand-in instead, so that
 *   they never reach the user's systemd instance.
 */
class SystemdSchedulerService(
	private val userHome: Path = Path(System.getProperty("user.home")),
	private val systemctl: String = "systemctl",
) : OsSchedulerService {

	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		val logFile = logFilePath?.let { appendableLogFile(it).toString() }
		check(isSystemdAvailable()) {
			noSystemdMessage(cliExecutablePath, runAtHour, runAtMinute, logFile)
		}
		val serviceUnit = renderServiceUnit(cliExecutablePath, logFile)
		val timerUnit = renderTimerUnit(runAtHour, runAtMinute)
		val unitDir = userUnitDir()
		unitDir.createDirectories()
		unitDir.resolve(SERVICE_UNIT).writeText(serviceUnit)
		unitDir.resolve(TIMER_UNIT).writeText(timerUnit)
		run(systemctl, "--user", "daemon-reload")
		run(systemctl, "--user", "enable", "--now", TIMER_UNIT)
	}

	override fun uninstall() {
		runQuietly(systemctl, "--user", "disable", "--now", TIMER_UNIT)
		val unitDir = userUnitDir()
		unitDir.resolve(TIMER_UNIT).deleteIfExists()
		unitDir.resolve(SERVICE_UNIT).deleteIfExists()
		runQuietly(systemctl, "--user", "daemon-reload")
	}

	override fun isInstalled(): Boolean =
		userUnitDir().resolve(TIMER_UNIT).exists()

	/**
	 * Whether a usable systemd user instance is reachable, without which [install] fails, as in
	 * containers, on WSL without systemd and on distributions without systemd.
	 */
	override fun isAvailable(): Boolean = isSystemdAvailable()

	/**
	 * Render the `oneshot` service unit that runs `omnisign renew`.
	 *
	 * The executable's path is quoted, and a `%` in either path is doubled, as systemd would take
	 * it for a specifier. Nothing else needs escaping: systemd expands no variable in the path of
	 * the executable it runs, and reads no quote or escape in an `append:` path. It refuses outright
	 * an executable whose path holds a quote, a backslash or a control character, so such a path is
	 * refused here, before any unit is written.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable.
	 * @param logFilePath Optional log file, as [install] checked it; when set, stdout and stderr are
	 *   appended to it.
	 * @return The full contents of the `*.service` unit file.
	 * @throws IllegalArgumentException When systemd cannot run an executable at [cliExecutablePath].
	 */
	internal fun renderServiceUnit(cliExecutablePath: String, logFilePath: String?): String {
		require(cliExecutablePath.none { it < ' ' || it == '\u007F' || it in "\"'\\" }) {
			"systemd cannot run OmniSign from a path that holds a quote, a backslash or a control " +
				"character: \"$cliExecutablePath\". Install OmniSign in a folder whose path has none."
		}
		return buildString {
			appendLine("[Unit]")
			appendLine("Description=OmniSign daily archival re-timestamping")
			appendLine()
			appendLine("[Service]")
			appendLine("Type=oneshot")
			appendLine("ExecStart=\"${cliExecutablePath.escapeSpecifiers()}\" renew")
			if (logFilePath != null) {
				appendLine("StandardOutput=append:${logFilePath.escapeSpecifiers()}")
				appendLine("StandardError=append:${logFilePath.escapeSpecifiers()}")
			}
		}
	}

	/**
	 * Render the timer unit with a daily trigger and missed-run catch-up.
	 *
	 * @param runAtHour Hour of day (0–23) for the daily run.
	 * @param runAtMinute Minute (0–59) for the daily run.
	 * @return The full contents of the `*.timer` unit file.
	 */
	internal fun renderTimerUnit(runAtHour: Int, runAtMinute: Int): String =
		buildString {
			appendLine("[Unit]")
			appendLine("Description=OmniSign daily archival re-timestamping timer")
			appendLine()
			appendLine("[Timer]")
			appendLine("OnCalendar=*-*-* %02d:%02d:00".format(runAtHour, runAtMinute))
			appendLine("Persistent=true")
			appendLine()
			appendLine("[Install]")
			appendLine("WantedBy=timers.target")
		}

	/**
	 * Build the actionable error shown when no usable systemd user instance is found.
	 *
	 * The message names the likely environments (containers, WSL, non-systemd distros) and
	 * prints both the bare `omnisign renew` command and a ready-to-paste crontab line, so the
	 * user can wire renewal into whatever scheduler their platform provides. Both quote the
	 * paths for the shell where it would otherwise change them.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable.
	 * @param runAtHour Hour of day (0–23) used in the example crontab line.
	 * @param runAtMinute Minute (0–59) used in the example crontab line.
	 * @param logFilePath Optional log file reflected in the example crontab line.
	 * @return The multi-line error message.
	 */
	internal fun noSystemdMessage(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	): String =
		buildString {
			appendLine("OmniSign's automatic scheduler requires systemd, but no usable user")
			appendLine("instance was found (systemctl --user is unavailable). This is expected in")
			appendLine("containers, on WSL without systemd, and on non-systemd distributions.")
			appendLine()
			appendLine("To schedule renewal yourself, run this command once per day with whatever")
			appendLine("scheduler your platform provides (cron, supercronic, a Kubernetes CronJob, …):")
			appendLine()
			appendLine("\t${cliExecutablePath.shellWord()} renew")
			appendLine()
			appendLine("For example, a crontab line that runs daily at %02d:%02d:".format(runAtHour, runAtMinute))
			appendLine()
			append("\t${manualCronLine(cliExecutablePath, runAtHour, runAtMinute, logFilePath)}")
		}

	/**
	 * Build an example crontab line that runs `omnisign renew` at the configured time.
	 *
	 * Each path is one shell word, quoted where the shell would change it, and with each `%` escaped
	 * outside the quotes, as cron ends the command at a bare `%`.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable.
	 * @param runAtHour Hour of day (0–23) for the daily run.
	 * @param runAtMinute Minute (0–59) for the daily run.
	 * @param logFilePath Optional log file appended via shell redirection.
	 * @return A single crontab line (no trailing newline).
	 */
	internal fun manualCronLine(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	): String {
		val redirect = if (logFilePath != null) " >> ${logFilePath.cronWord()} 2>&1" else ""
		return "$runAtMinute $runAtHour * * * ${cliExecutablePath.cronWord()} renew$redirect"
	}

	/**
	 * Check the log file each run's output is to be appended to, and return the path to write into
	 * the service unit.
	 *
	 * The user's service manager opens the file itself, as the user, before it starts
	 * `omnisign renew`, and a run whose file it cannot open fails with status 209/STDOUT without
	 * OmniSign starting, so nothing would record or report the failure; the shell a cron line starts
	 * fails the same way on its redirection. The path must therefore be absolute, as systemd ignores
	 * a relative one, and must stay on its unit file line as written: it may hold no control
	 * character and may not end with a space, which systemd strips, or with a backslash, which joins
	 * the next line to it. The file is then created with its missing folders and opened for
	 * appending, with nothing appended, which leaves what it holds untouched, so that a path the
	 * user cannot write to is refused while the job is registered. The path is normalized first, as
	 * systemd also ignores one with a `..` segment.
	 *
	 * @param logFilePath The log file as the user gave it.
	 * @return The normalized log file, which now exists and can be appended to.
	 * @throws IllegalArgumentException When the path breaks one of these rules, or the file cannot
	 *   be created or opened for appending; the message names the path and why it was refused, and
	 *   suggests a path under the user's home.
	 */
	private fun appendableLogFile(logFilePath: String): Path {
		val staysOnItsLine = logFilePath.none { it < ' ' || it == '\u007F' } &&
			!logFilePath.endsWith(' ') && !logFilePath.endsWith('\\')
		require(staysOnItsLine) {
			"A systemd unit cannot hold a renewal log file path with a control character in it, or one " +
				"that ends with a space or a backslash: \"$logFilePath\"."
		}
		val path = Path(logFilePath)
		require(path.isAbsolute) {
			"The renewal log file must be an absolute path, such as ${suggestedLogFile()}, not \"$logFilePath\"."
		}
		val logFile = path.normalize()
		try {
			logFile.parent?.createDirectories()
			logFile.writeBytes(ByteArray(0), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
		} catch (e: IOException) {
			val reason = when (e) {
				is AccessDeniedException -> "permission denied"
				is FileAlreadyExistsException -> "${e.file} is not a folder"
				else -> e.message ?: e.javaClass.simpleName
			}
			throw IllegalArgumentException(
				"The renewal log file $logFile cannot be written to ($reason). Every run would fail before " +
					"OmniSign starts, as its output could not be saved. Choose a file you can write to, such " +
					"as ${suggestedLogFile()}.",
				e,
			)
		}
		return logFile
	}

	/**
	 * The log file offered when the user's one cannot be used: `renew.log` in OmniSign's folder of
	 * the XDG state directory under [userHome], which the user can create and write to.
	 */
	private fun suggestedLogFile(): Path = userHome / ".local" / "state" / "omnisign" / "renew.log"

	/**
	 * Resolve the systemd user unit directory under [userHome].
	 *
	 * @return The `~/.config/systemd/user` path.
	 */
	private fun userUnitDir(): Path = userHome / ".config" / "systemd" / "user"

	/**
	 * Probe whether a usable systemd user instance is reachable.
	 *
	 * Uses `systemctl --user show-environment`, which exits zero only when the per-user
	 * systemd manager is running and reachable. Returns `false` when `systemctl` is absent
	 * (the binary cannot be started) or the user bus is unavailable.
	 *
	 * @return `true` when a systemd user timer can be installed.
	 */
	private fun isSystemdAvailable(): Boolean =
		try {
			val process = ProcessBuilder(systemctl, "--user", "show-environment")
				.redirectErrorStream(true)
				.start()
			process.inputStream.readAllBytes()
			process.waitFor() == 0
		} catch (e: IOException) {
			logger.debug(e) { "systemctl is not available on this host" }
			false
		}

	/**
	 * Run a command and fail with its captured output when the exit code is non-zero.
	 *
	 * @param args The command and its arguments.
	 */
	private fun run(vararg args: String) {
		val process = ProcessBuilder(*args).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		val exitCode = process.waitFor()
		if (exitCode != 0) {
			error("systemctl command failed (exit $exitCode): ${args.joinToString(" ")}\n$output")
		}
	}

	/**
	 * Run a best-effort command, logging and swallowing any failure.
	 *
	 * Used for teardown steps (disable, daemon-reload) that must not abort [uninstall] when
	 * the timer was already gone or systemd is no longer reachable.
	 *
	 * @param args The command and its arguments.
	 */
	private fun runQuietly(vararg args: String) {
		try {
			val process = ProcessBuilder(*args).redirectErrorStream(true).start()
			process.inputStream.readAllBytes()
			process.waitFor()
		} catch (e: Exception) {
			logger.debug(e) { "Ignoring failure of best-effort command: ${args.joinToString(" ")}" }
		}
	}

	/**
	 * This string with each `%` doubled, which a systemd unit reads back as a single `%` where it
	 * would otherwise take it for a specifier.
	 */
	private fun String.escapeSpecifiers(): String = replace("%", "%%")

	/**
	 * This string as one POSIX shell word: as it is when it holds only characters the shell takes
	 * literally, otherwise in single quotes, with each single quote in it written as `'\''`.
	 */
	private fun String.shellWord(): String =
		if (isNotEmpty() && all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "/._-+:@," }) {
			this
		} else {
			"'" + replace("'", "'\\''") + "'"
		}

	/**
	 * This string as one word of a crontab command: its [shellWord], with each `%` taken out of the
	 * quotes and escaped, as cron turns a bare `%` into a line break and drops the backslash of `\%`.
	 */
	private fun String.cronWord(): String = shellWord().replace("%", "'\\%'")

	companion object {
		/** File name of the generated systemd timer unit. */
		private const val TIMER_UNIT = "${OsSchedulerService.JOB_TAG}.timer"

		/** File name of the generated systemd service unit. */
		private const val SERVICE_UNIT = "${OsSchedulerService.JOB_TAG}.service"
	}
}
