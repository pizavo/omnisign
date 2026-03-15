package cz.pizavo.omnisign.commands.schedule

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.data.service.OsSchedulerService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Removes the daily `omnisign renew` OS job if it exists.
 */
class ScheduleUninstall : CliktCommand(name = "uninstall"), KoinComponent {
	private val scheduler: OsSchedulerService by inject()
	
	override fun help(context: Context): String =
		"Remove the daily automatic re-timestamping job from the OS scheduler"
	
	override fun run() {
		scheduler.uninstall()
		echo("✅ Daily renewal job removed (or was not registered).")
	}
}

