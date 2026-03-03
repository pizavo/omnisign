package cz.pizavo.omnisign.data.service

/**
 * [OsSchedulerService] implementation for Windows that manages a single daily task via
 * the `schtasks` command-line utility.
 *
 * The task is identified by the name [OsSchedulerService.JOB_TAG].  Each `install` call
 * deletes any existing task with that name and recreates it, making the operation
 * idempotent.
 */
class WindowsTaskSchedulerService : OsSchedulerService {
	
	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		runQuietly("schtasks", "/delete", "/tn", OsSchedulerService.JOB_TAG, "/f")
		val startTime = "%02d:%02d".format(runAtHour, runAtMinute)
		val command = buildRenewCommand(cliExecutablePath, logFilePath)
		
		run(
			"schtasks", "/create",
			"/tn", OsSchedulerService.JOB_TAG,
			"/tr", command,
			"/sc", "DAILY",
			"/st", startTime,
			"/f",
		)
	}
	
	override fun uninstall() {
		runQuietly("schtasks", "/delete", "/tn", OsSchedulerService.JOB_TAG, "/f")
	}
	
	override fun isInstalled(): Boolean {
		val result = ProcessBuilder("schtasks", "/query", "/tn", OsSchedulerService.JOB_TAG)
			.redirectErrorStream(true)
			.start()
		result.waitFor()
		return result.exitValue() == 0
	}
	
	private fun buildRenewCommand(cliExecutablePath: String, logFilePath: String?): String {
		val base = "cmd /c \"${cliExecutablePath.escapeForCmd()} renew"
		return if (logFilePath != null) {
			"$base >> ${logFilePath.escapeForCmd()} 2>&1\""
		} else {
			"$base\""
		}
	}
	
	private fun run(vararg args: String) {
		val process = ProcessBuilder(*args).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		val exitCode = process.waitFor()
		if (exitCode != 0) {
			error("schtasks failed (exit $exitCode): $output")
		}
	}
	
	private fun runQuietly(vararg args: String) {
		ProcessBuilder(*args).redirectErrorStream(true).start().waitFor()
	}
	
	private fun String.escapeForCmd(): String = replace("\"", "\\\"")
}


