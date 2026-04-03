package cz.pizavo.omnisign.domain.port

/**
 * Platform-agnostic port for managing the OS-level daily renewal scheduler.
 *
 * On JVM the implementation delegates to [cz.pizavo.omnisign.data.service.OsSchedulerService]
 * (crontab on Linux/macOS, Task Scheduler on Windows). Other platforms may leave
 * this port unregistered, in which case the scheduler controls are hidden in the UI.
 */
interface SchedulerPort {

	/**
	 * Register (or replace) the daily renewal job in the OS scheduler.
	 *
	 * @param cliExecutablePath Absolute path to the OmniSign executable invoked each day.
	 * @param runAtHour Hour of day (0–23) at which the job should be triggered.
	 * @param runAtMinute Minute (0–59) at which the job should be triggered.
	 * @param logFilePath Optional append-only log file for renewal output.
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
	 * Return `true` if the daily renewal job is currently registered in the OS scheduler.
	 */
	fun isInstalled(): Boolean
}


