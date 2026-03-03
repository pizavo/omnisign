package cz.pizavo.omnisign.commands.schedule.job

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.domain.usecase.ManageRenewalJobsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * List all configured RenewalJobs.
 */
class ScheduleJobList : CliktCommand(name = "list"), KoinComponent {
	private val manageJobs: ManageRenewalJobsUseCase by inject()
	
	override fun help(context: Context): String = "List all configured renewal jobs"
	
	override fun run(): Unit = runBlocking {
		manageJobs.list().fold(
			ifLeft = { echo("Failed to load jobs: ${it.message}", err = true) },
			ifRight = { jobs ->
				if (jobs.isEmpty()) {
					echo("No renewal jobs configured.")
					return@fold
				}
				echo("Configured renewal jobs:\n")
				for ((_, job) in jobs.entries.sortedBy { it.key }) {
					echo("  ${job.name}")
					job.globs.forEach { echo("    glob        : $it") }
					echo("    buffer days : ${job.renewalBufferDays}")
					echo("    notify      : ${job.notify}")
					job.profile?.let { echo("    profile     : $it") }
					job.logFile?.let { echo("    log file    : $it") }
					echo("")
				}
			}
		)
	}
}

