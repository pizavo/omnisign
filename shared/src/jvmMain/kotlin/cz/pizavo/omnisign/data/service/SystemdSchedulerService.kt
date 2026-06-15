package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
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
 * systemd availability is probed (`systemctl --user`) inside [install], never in the
 * constructor, so constructing this service — for example when the Koin graph is built inside
 * a container that has no systemd — performs no I/O and cannot fail. When systemd is
 * unavailable [install] throws an [IllegalStateException] whose message explains how to
 * schedule `omnisign renew` with another scheduler (cron, supercronic, a Kubernetes
 * CronJob, …).
 */
class SystemdSchedulerService : OsSchedulerService {

	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		check(isSystemdAvailable()) {
			noSystemdMessage(cliExecutablePath, runAtHour, runAtMinute, logFilePath)
		}
		val unitDir = userUnitDir()
		unitDir.createDirectories()
		unitDir.resolve(SERVICE_UNIT).writeText(renderServiceUnit(cliExecutablePath, logFilePath))
		unitDir.resolve(TIMER_UNIT).writeText(renderTimerUnit(runAtHour, runAtMinute))
		run("systemctl", "--user", "daemon-reload")
		run("systemctl", "--user", "enable", "--now", TIMER_UNIT)
	}

	override fun uninstall() {
		runQuietly("systemctl", "--user", "disable", "--now", TIMER_UNIT)
		val unitDir = userUnitDir()
		unitDir.resolve(TIMER_UNIT).deleteIfExists()
		unitDir.resolve(SERVICE_UNIT).deleteIfExists()
		runQuietly("systemctl", "--user", "daemon-reload")
	}

	override fun isInstalled(): Boolean =
		userUnitDir().resolve(TIMER_UNIT).exists()

	/**
	 * Render the `oneshot` service unit that runs `omnisign renew`.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable.
	 * @param logFilePath Optional log file; when set, stdout and stderr are appended to it.
	 * @return The full contents of the `*.service` unit file.
	 */
	internal fun renderServiceUnit(cliExecutablePath: String, logFilePath: String?): String =
		buildString {
			appendLine("[Unit]")
			appendLine("Description=OmniSign daily archival re-timestamping")
			appendLine()
			appendLine("[Service]")
			appendLine("Type=oneshot")
			appendLine("ExecStart=${execStart(cliExecutablePath)}")
			if (logFilePath != null) {
				appendLine("StandardOutput=append:$logFilePath")
				appendLine("StandardError=append:$logFilePath")
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
	 * user can wire renewal into whatever scheduler their platform provides.
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
			appendLine("\t$cliExecutablePath renew")
			appendLine()
			appendLine("For example, a crontab line that runs daily at %02d:%02d:".format(runAtHour, runAtMinute))
			appendLine()
			append("\t${manualCronLine(cliExecutablePath, runAtHour, runAtMinute, logFilePath)}")
		}

	/**
	 * Build an example crontab line that runs `omnisign renew` at the configured time.
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
		val redirect = if (logFilePath != null) " >> ${logFilePath.quoteIfNeeded()} 2>&1" else ""
		return "$runAtMinute $runAtHour * * * ${cliExecutablePath.quoteIfNeeded()} renew$redirect"
	}

	/**
	 * Build the `ExecStart` value, quoting the executable path when it contains spaces.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable.
	 * @return The `ExecStart` command value.
	 */
	private fun execStart(cliExecutablePath: String): String {
		val exe = if (cliExecutablePath.contains(' ')) "\"$cliExecutablePath\"" else cliExecutablePath
		return "$exe renew"
	}

	/**
	 * Resolve the systemd user unit directory under the current user's home.
	 *
	 * @return The `~/.config/systemd/user` path.
	 */
	private fun userUnitDir(): Path =
		Path.of(System.getProperty("user.home"), ".config", "systemd", "user")

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
			val process = ProcessBuilder("systemctl", "--user", "show-environment")
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
	 * Quote a string for use in a crontab line when it contains spaces.
	 *
	 * @return The original string, wrapped in double quotes only if it contains a space.
	 */
	private fun String.quoteIfNeeded(): String =
		if (contains(' ')) "\"$this\"" else this

	companion object {
		/** File name of the generated systemd timer unit. */
		private const val TIMER_UNIT = "${OsSchedulerService.JOB_TAG}.timer"

		/** File name of the generated systemd service unit. */
		private const val SERVICE_UNIT = "${OsSchedulerService.JOB_TAG}.service"
	}
}
