package cz.pizavo.omnisign.data.service

/**
 * [OsSchedulerService] implementation for Windows that manages a single daily task.
 *
 * **install** uses PowerShell's `Register-ScheduledTask` cmdlet because
 * `New-ScheduledTaskAction` cleanly separates the executable path from its arguments
 * via `-Execute` / `-Argument`, avoiding the nested-quoting issue where Java's
 * [ProcessBuilder] escapes embedded double-quotes with backslashes (`\"`) but
 * `schtasks /create /tr` does not use the MSVC quoting convention.
 *
 * **isInstalled** and **uninstall** use the lightweight `schtasks` CLI instead
 * because their arguments are simple strings with no quoting ambiguity, and
 * `schtasks` starts significantly faster than PowerShell (no .NET runtime init).
 *
 * The task is identified by the name [OsSchedulerService.JOB_TAG]. Each `install` call
 * replaces any existing task with that name via `-Force`, making the operation idempotent.
 */
class WindowsTaskSchedulerService : OsSchedulerService {

	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		val startTime = "%02d:%02d".format(runAtHour, runAtMinute)
		val (exe, args) = buildAction(cliExecutablePath, logFilePath)
		val psCommand = buildString {
			append("\$action = New-ScheduledTaskAction")
			append(" -Execute '${exe.escapeSingleQuote()}'")
			append(" -Argument '${args.escapeSingleQuote()}'")
			append("; \$trigger = New-ScheduledTaskTrigger -Daily -At '${startTime}'")
			append("; Register-ScheduledTask")
			append(" -TaskName '${OsSchedulerService.JOB_TAG}'")
			append(" -Action \$action -Trigger \$trigger -Force | Out-Null")
		}
		run("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand)
	}

	override fun uninstall() {
		runQuietly("schtasks", "/delete", "/tn", OsSchedulerService.JOB_TAG, "/f")
	}

	override fun isInstalled(): Boolean {
		val process = ProcessBuilder("schtasks", "/query", "/tn", OsSchedulerService.JOB_TAG)
			.redirectErrorStream(true)
			.start()
		process.inputStream.readAllBytes()
		return process.waitFor() == 0
	}

	/**
	 * Build the scheduled task action pair (executable, arguments).
	 *
	 * When a [logFilePath] is provided, the action delegates to `cmd` so that
	 * shell-level output redirection (`>>`) works. Otherwise the OmniSign
	 * executable is invoked directly.
	 *
	 * @return A pair of (executable, arguments) for [New-ScheduledTaskAction].
	 */
	private fun buildAction(cliExecutablePath: String, logFilePath: String?): Pair<String, String> =
		if (logFilePath != null) {
			"cmd" to "/c \"$cliExecutablePath\" renew >> \"$logFilePath\" 2>&1"
		} else {
			cliExecutablePath to "renew"
		}

	private fun run(vararg args: String) {
		val process = ProcessBuilder(*args).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		val exitCode = process.waitFor()
		if (exitCode != 0) {
			error("Scheduler command failed (exit $exitCode): $output")
		}
	}

	private fun runQuietly(vararg args: String) {
		ProcessBuilder(*args).redirectErrorStream(true).start().waitFor()
	}

	private fun String.escapeSingleQuote(): String = replace("'", "''")
}
