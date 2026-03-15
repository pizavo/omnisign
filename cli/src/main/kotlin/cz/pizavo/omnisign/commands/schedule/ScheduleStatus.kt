package cz.pizavo.omnisign.commands.schedule

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.data.service.OsSchedulerService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Reports whether the daily `omnisign renew` OS job is currently registered.
 */
class ScheduleStatus : CliktCommand(name = "status"), KoinComponent {
	private val scheduler: OsSchedulerService by inject()
	
	override fun help(context: Context): String =
		"Show whether the daily automatic re-timestamping job is registered"
	
	override fun run() {
		if (scheduler.isInstalled()) {
			echo("✅ Daily renewal job is installed.")
		} else {
			echo("⚪ Daily renewal job is NOT installed. Run `omnisign schedule install` to set it up.")
		}
	}
}

