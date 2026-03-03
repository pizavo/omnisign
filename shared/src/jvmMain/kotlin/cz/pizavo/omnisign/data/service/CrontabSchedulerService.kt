package cz.pizavo.omnisign.data.service

/**
 * [OsSchedulerService] implementation for Linux and macOS that manages a single
 * `crontab` entry identified by a comment tag.
 *
 * The installed crontab line takes the form:
 * ```
 * MM HH * * * /path/to/omnisign renew >> /path/to/log 2>&1 # omnisign-renewal
 * ```
 * The existing crontab for the current user is read, the old entry (if any) is removed,
 * and the new entry is written back atomically via `crontab -`.
 */
class CrontabSchedulerService : OsSchedulerService {
	
	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		val existing = readCrontab().lines().filter { !it.contains(OsSchedulerService.JOB_TAG) }
		val redirect = if (logFilePath != null) " >> ${logFilePath.quoteIfNeeded()} 2>&1" else ""
		val newLine =
			"$runAtMinute $runAtHour * * * ${cliExecutablePath.quoteIfNeeded()} renew$redirect # ${OsSchedulerService.JOB_TAG}"
		val updated = (existing + newLine).joinToString("\n").trimStart('\n') + "\n"
		writeCrontab(updated)
	}
	
	override fun uninstall() {
		val existing = readCrontab()
		val updated = existing.lines()
			.filter { !it.contains(OsSchedulerService.JOB_TAG) }
			.joinToString("\n")
			.trimStart('\n')
		writeCrontab(if (updated.isBlank()) "" else "$updated\n")
	}
	
	override fun isInstalled(): Boolean =
		readCrontab().lines().any { it.contains(OsSchedulerService.JOB_TAG) }
	
	private fun readCrontab(): String {
		val result = ProcessBuilder("crontab", "-l")
			.redirectErrorStream(true)
			.start()
		val output = result.inputStream.bufferedReader().readText()
		result.waitFor()
		return if (output.contains("no crontab for")) "" else output
	}
	
	private fun writeCrontab(content: String) {
		val process = ProcessBuilder("crontab", "-")
			.redirectErrorStream(true)
			.start()
		process.outputStream.bufferedWriter().use { it.write(content) }
		val exitCode = process.waitFor()
		if (exitCode != 0) {
			val err = process.inputStream.bufferedReader().readText()
			error("crontab write failed (exit $exitCode): $err")
		}
	}
	
	private fun String.quoteIfNeeded(): String =
		if (contains(' ')) "\"$this\"" else this
}

