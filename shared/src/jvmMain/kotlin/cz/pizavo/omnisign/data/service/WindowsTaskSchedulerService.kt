package cz.pizavo.omnisign.data.service

import kotlin.io.encoding.Base64

/**
 * [OsSchedulerService] implementation for Windows that manages a single daily task.
 *
 * **install** uses PowerShell's `Register-ScheduledTask` cmdlet because
 * `New-ScheduledTaskAction` keeps the executable apart from its arguments
 * (`-Execute` / `-Argument`), where `schtasks /create /tr` takes them as one command
 * line. The registration script reaches PowerShell through `-EncodedCommand`, Base64 of
 * its UTF-16LE bytes, rather than `-Command`: by default Java's [ProcessBuilder] leaves the
 * double quotes inside an argument unescaped on Windows, and PowerShell's command-line
 * parsing then strips them, so the quotes around the paths in the task's arguments would be
 * lost and a path with a space in it cut there. Encoding bypasses that parsing altogether.
 * PowerShell writes the errors of an encoded script as CLIXML, so the script writes the message of
 * its own error as plain text instead (see [buildRegisterScript]).
 *
 * The registered task sets `StartWhenAvailable`, so a daily run missed while the machine was
 * off or asleep is caught up once the machine is next available instead of being skipped
 * until the next day.
 *
 * **isInstalled** and **uninstall** use the lightweight `schtasks` CLI instead
 * because their arguments are simple strings with no quoting ambiguity, and
 * `schtasks` starts significantly faster than PowerShell (no .NET runtime init).
 *
 * The task is identified by [taskName], which is [OsSchedulerService.JOB_TAG] unless the caller
 * names another; tests do, so that they never replace or remove a renewal task the user installed.
 * Each `install` call replaces any existing task with that name via `-Force`, making the operation
 * idempotent.
 *
 * @param taskName Name of the scheduled task this service registers, queries and removes.
 */
class WindowsTaskSchedulerService(
	private val taskName: String = OsSchedulerService.JOB_TAG,
) : OsSchedulerService {

	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		run(*buildInstallCommand(cliExecutablePath, runAtHour, runAtMinute, logFilePath).toTypedArray())
	}

	/**
	 * Build the PowerShell invocation that [install] runs: the script of [buildRegisterScript] for
	 * the action of [buildAction], handed over through `-EncodedCommand` so that no quote in it is
	 * lost on the way (see the class description).
	 *
	 * @return The program and its arguments, for [ProcessBuilder].
	 */
	internal fun buildInstallCommand(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	): List<String> {
		val startTime = "%02d:%02d".format(runAtHour, runAtMinute)
		val (exe, args) = buildAction(cliExecutablePath, logFilePath)
		val script = buildRegisterScript(exe, args, startTime)
		val encoded = Base64.Default.encode(script.toByteArray(Charsets.UTF_16LE))
		return listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
	}

	/**
	 * Build the PowerShell command that registers (or replaces) the daily task.
	 *
	 * The task settings enable `StartWhenAvailable`, so a run missed because the machine was
	 * off or asleep at [startTime] is executed as soon as the machine is next available rather
	 * than skipped until the following day. Task Scheduler coalesces multiple missed
	 * occurrences into a single catch-up run.
	 *
	 * The script turns PowerShell's progress records off, stops at its first error, and writes that
	 * error's message to standard output, encoded as UTF-8, before it exits with 1, so that
	 * [install] reports the message as PowerShell gave it. Left to PowerShell, the error would reach
	 * standard error as CLIXML, the form in which PowerShell writes the errors of a script handed
	 * over through `-EncodedCommand`.
	 *
	 * @param exe Executable to launch (the OmniSign binary, or `cmd` when redirecting output).
	 * @param args Arguments passed to [exe].
	 * @param startTime Daily trigger time in `HH:mm` form.
	 * @return The PowerShell script that [buildInstallCommand] encodes.
	 */
	internal fun buildRegisterScript(exe: String, args: String, startTime: String): String =
		buildString {
			append("\$ProgressPreference = 'SilentlyContinue'; \$ErrorActionPreference = 'Stop'; try { ")
			append("\$action = New-ScheduledTaskAction")
			append(" -Execute '${exe.escapeSingleQuote()}'")
			append(" -Argument '${args.escapeSingleQuote()}'")
			append("; \$trigger = New-ScheduledTaskTrigger -Daily -At '${startTime}'")
			append("; \$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable")
			append("; Register-ScheduledTask")
			append(" -TaskName '${taskName.escapeSingleQuote()}'")
			append(" -Action \$action -Trigger \$trigger -Settings \$settings -Force | Out-Null")
			append(" } catch { \$bytes = [Text.Encoding]::UTF8.GetBytes(\$_.Exception.Message)")
			append("; \$out = [Console]::OpenStandardOutput(); \$out.Write(\$bytes, 0, \$bytes.Length); \$out.Flush()")
			append("; exit 1 }")
		}

	override fun uninstall() {
		runQuietly("schtasks", "/delete", "/tn", taskName, "/f")
	}

	override fun isInstalled(): Boolean {
		val process = ProcessBuilder("schtasks", "/query", "/tn", taskName)
			.redirectErrorStream(true)
			.start()
		process.inputStream.readAllBytes()
		return process.waitFor() == 0
	}

	/**
	 * Build the scheduled task action pair (executable, arguments).
	 *
	 * When a [logFilePath] is provided, the action delegates to `cmd` so that
	 * shell-level output redirection (`>>`) works. The executable and the log file are
	 * quoted, so either may contain spaces, and the whole command line is wrapped in one
	 * more pair of quotes and run with `/s`, under which `cmd` strips exactly that outer
	 * pair. Without it, `cmd /c` given more than two quotes strips the first and the last
	 * of them, the opening quote of the executable's path and the closing quote of the log
	 * file's. Otherwise the OmniSign executable is invoked directly, its path in a field of
	 * its own.
	 *
	 * @return A pair of (executable, arguments) for `New-ScheduledTaskAction`.
	 */
	internal fun buildAction(cliExecutablePath: String, logFilePath: String?): Pair<String, String> =
		if (logFilePath != null) {
			"cmd" to "/s /c \"\"$cliExecutablePath\" renew >> \"$logFilePath\" 2>&1\""
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
