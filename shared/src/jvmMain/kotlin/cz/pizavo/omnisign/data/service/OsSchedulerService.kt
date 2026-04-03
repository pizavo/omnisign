package cz.pizavo.omnisign.data.service

/**
 * Abstraction over the OS-level task scheduler used to register a single daily
 * `omnisign renew` job.
 *
 * Only one system-wide job is needed because `omnisign renew` iterates all configured
 * [cz.pizavo.omnisign.domain.model.config.RenewalJob] entries in a single run.
 * Platform-specific implementations use `crontab` (Linux / macOS) or `schtasks`
 * (Windows) via [ProcessBuilder]; no external library is required.
 */
interface OsSchedulerService {
	/**
	 * Register (or replace) the daily renewal job in the OS scheduler.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable (CLI or desktop app)
	 *   that will be called each day.
	 * @param runAtHour Hour of day (0–23) at which the job should be triggered.
	 * @param runAtMinute Minute (0–59) at which the job should be triggered.
	 * @param logFilePath Optional path to an append-only log file.  When supplied, the
	 *   scheduler redirects stdout and stderr of each run to this file.
	 */
	fun install(
		cliExecutablePath: String,
		runAtHour: Int = 2,
		runAtMinute: Int = 0,
		logFilePath: String? = null,
	)
	
	/**
	 * Remove the daily renewal job from the OS scheduler.
	 * Does nothing if the job is not currently registered.
	 */
	fun uninstall()
	
	/**
	 * Return true if the daily renewal job is currently registered in the OS scheduler.
	 */
	fun isInstalled(): Boolean
	
	companion object {
		/** Tag embedded in the crontab comment / task name to identify our entry. */
		const val JOB_TAG = "omnisign-renewal"
	}
}

