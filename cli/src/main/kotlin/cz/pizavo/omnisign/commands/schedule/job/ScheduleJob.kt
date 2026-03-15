package cz.pizavo.omnisign.commands.schedule.job

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Parent command for managing individual RenewalJob entries in the application config.
 */
class ScheduleJob : CliktCommand(name = "job") {
	init {
		subcommands(ScheduleJobAdd(), ScheduleJobList(), ScheduleJobRemove())
	}
	
	override fun help(context: Context): String = "Manage renewal jobs"
	
	override fun run() = Unit
}


