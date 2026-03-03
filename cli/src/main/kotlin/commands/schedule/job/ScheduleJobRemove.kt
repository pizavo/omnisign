package cz.pizavo.omnisign.commands.schedule.job

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManageRenewalJobsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Remove a RenewalJob from the configuration by name.
 */
class ScheduleJobRemove : CliktCommand(name = "remove"), KoinComponent {
	private val manageJobs: ManageRenewalJobsUseCase by inject()
	
	private val name by argument(help = "Name of the renewal job to remove")
	
	override fun help(context: Context): String = "Remove a renewal job"
	
	override fun run(): Unit = runBlocking {
		manageJobs.remove(name).fold(
			ifLeft = { echo(it.message, err = true) },
			ifRight = { echo("Renewal job '$name' removed.") }
		)
	}
}

