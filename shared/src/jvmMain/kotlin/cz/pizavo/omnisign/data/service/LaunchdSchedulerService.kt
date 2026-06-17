package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * [OsSchedulerService] implementation for macOS that manages the daily renewal job as a
 * **launchd LaunchAgent**.
 *
 * [install] writes a property list to `~/Library/LaunchAgents/<label>.plist` with a
 * `StartCalendarInterval` trigger and loads it into the current user's GUI domain via
 * `launchctl bootstrap`. Running as a LaunchAgent rather than a system LaunchDaemon needs no
 * root privileges.
 *
 * launchd provides missed-run catch-up: when the machine is asleep or off at the scheduled
 * time the job runs once on the next wake, and multiple missed intervals are coalesced into a
 * single run.
 */
class LaunchdSchedulerService : OsSchedulerService {

	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		val plist = plistPath()
		plist.parent?.createDirectories()
		plist.writeText(renderPlist(cliExecutablePath, runAtHour, runAtMinute, logFilePath))
		val domain = "gui/${currentUid()}"
		runQuietly("launchctl", "bootout", "$domain/$LAUNCHD_LABEL")
		run("launchctl", "bootstrap", domain, plist.toString())
	}

	override fun uninstall() {
		runQuietly("launchctl", "bootout", "gui/${currentUid()}/$LAUNCHD_LABEL")
		plistPath().deleteIfExists()
	}

	override fun isInstalled(): Boolean =
		plistPath().exists()

	/**
	 * Render the LaunchAgent property list.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable.
	 * @param runAtHour Hour of day (0–23) for the daily run.
	 * @param runAtMinute Minute (0–59) for the daily run.
	 * @param logFilePath Optional log file; when set, stdout and stderr are written to it.
	 * @return The full contents of the `.plist` file.
	 */
	internal fun renderPlist(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	): String =
		buildString {
			appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
			appendLine("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
			appendLine("<plist version=\"1.0\">")
			appendLine("<dict>")
			appendLine("\t<key>Label</key>")
			appendLine("\t<string>$LAUNCHD_LABEL</string>")
			appendLine("\t<key>ProgramArguments</key>")
			appendLine("\t<array>")
			appendLine("\t\t<string>${cliExecutablePath.escapeXml()}</string>")
			appendLine("\t\t<string>renew</string>")
			appendLine("\t</array>")
			appendLine("\t<key>StartCalendarInterval</key>")
			appendLine("\t<dict>")
			appendLine("\t\t<key>Hour</key>")
			appendLine("\t\t<integer>$runAtHour</integer>")
			appendLine("\t\t<key>Minute</key>")
			appendLine("\t\t<integer>$runAtMinute</integer>")
			appendLine("\t</dict>")
			if (logFilePath != null) {
				appendLine("\t<key>StandardOutPath</key>")
				appendLine("\t<string>${logFilePath.escapeXml()}</string>")
				appendLine("\t<key>StandardErrorPath</key>")
				appendLine("\t<string>${logFilePath.escapeXml()}</string>")
			}
			appendLine("</dict>")
			append("</plist>")
		}

	/**
	 * Resolve the LaunchAgent plist path under the current user's home.
	 *
	 * @return The `~/Library/LaunchAgents/<label>.plist` path.
	 */
	private fun plistPath(): Path =
		Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "$LAUNCHD_LABEL.plist")

	/**
	 * Resolve the current user's numeric id for the `gui/<uid>` launchd domain target.
	 *
	 * @return The uid as reported by `id -u`.
	 */
	private fun currentUid(): String {
		val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
		val uid = process.inputStream.bufferedReader().readText().trim()
		process.waitFor()
		return uid
	}

	/**
	 * Escape XML metacharacters for inclusion in plist element text.
	 *
	 * @return The string with `&`, `<`, and `>` replaced by their XML entities.
	 */
	private fun String.escapeXml(): String =
		replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
			error("launchctl command failed (exit $exitCode): ${args.joinToString(" ")}\n$output")
		}
	}

	/**
	 * Run a best-effort command, logging and swallowing any failure.
	 *
	 * Used to remove a previously loaded agent before re-bootstrapping, where a missing agent
	 * is an expected and ignorable outcome.
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

	companion object {
		/** Reverse-DNS launchd label, also used as the plist file name. */
		private const val LAUNCHD_LABEL = "cz.pizavo.omnisign.renewal"
	}
}
