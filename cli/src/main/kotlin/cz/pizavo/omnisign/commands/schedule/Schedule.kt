package cz.pizavo.omnisign.commands.schedule

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import cz.pizavo.omnisign.commands.schedule.job.ScheduleJob

/**
 * Top-level CLI command that groups all subcommands for managing the OS-level daily
 * renewal cron job and the [cz.pizavo.omnisign.domain.model.config.RenewalJob] entries
 * stored in the application configuration.
 */
class Schedule : CliktCommand(name = "schedule") {
	init {
		subcommands(ScheduleInstall(), ScheduleUninstall(), ScheduleStatus(), ScheduleJob())
	}
	
	override fun help(context: Context): String =
		"Manage the automatic re-timestamping scheduler and renewal jobs"
	
	override fun run() = Unit
}


