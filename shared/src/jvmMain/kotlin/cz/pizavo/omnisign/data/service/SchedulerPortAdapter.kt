package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.port.SchedulerPort

/**
 * JVM implementation of [SchedulerPort] that delegates to an [OsSchedulerService].
 *
 * @param delegate The platform-specific scheduler service (crontab or schtasks).
 */
class SchedulerPortAdapter(
	private val delegate: OsSchedulerService,
) : SchedulerPort {

	override fun install(
		cliExecutablePath: String,
		runAtHour: Int,
		runAtMinute: Int,
		logFilePath: String?,
	) {
		delegate.install(cliExecutablePath, runAtHour, runAtMinute, logFilePath)
	}

	override fun uninstall() {
		delegate.uninstall()
	}

	override fun isInstalled(): Boolean =
		delegate.isInstalled()
}

