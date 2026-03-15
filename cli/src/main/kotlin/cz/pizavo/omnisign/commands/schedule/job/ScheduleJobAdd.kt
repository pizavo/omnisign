package cz.pizavo.omnisign.commands.schedule.job

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.usecase.ManageRenewalJobsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Add or replace a RenewalJob in the configuration.
 */
class ScheduleJobAdd : CliktCommand(name = "add"), KoinComponent {
	private val manageJobs: ManageRenewalJobsUseCase by inject()
	
	private val name by argument(help = "Unique name for this renewal job")
	private val globs by option(
		"-g", "--glob",
		help = "Glob pattern matching PDF files to watch. Can be specified multiple times."
	).multiple(required = true)
	private val bufferDays by option(
		"-b", "--buffer-days",
		help = "Days before timestamp certificate expiry at which re-timestamping is triggered. " +
				"Default: ${ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS}"
	).int().default(ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS)
	private val profile by option(
		"--profile",
		help = "Named configuration profile to use for TSA and revocation settings."
	)
	private val logFile by option(
		"--log-file",
		help = "Absolute path to an append-only log file for this job's renewal output."
	)
	private val noNotify by option(
		"--no-notify",
		help = "Disable OS desktop notifications for this job. Recommended for headless server deployments."
	).flag(default = false)
	
	override fun help(context: Context): String = "Add or replace a renewal job"
	
	override fun run(): Unit = runBlocking {
		val job = RenewalJob(
			name = name,
			globs = globs,
			renewalBufferDays = bufferDays,
			profile = profile,
			logFile = logFile,
			notify = !noNotify,
		)
		manageJobs.upsert(job).fold(
			ifLeft = { echo("Failed to save job: ${it.message}", err = true) },
			ifRight = {
				echo("Renewal job '$name' saved.")
				echo("   Globs        : ${globs.joinToString()}")
				echo("   Buffer days  : $bufferDays")
				echo("   Notify       : ${!noNotify}")
				profile?.let { p -> echo("   Profile      : $p") }
				logFile?.let { l -> echo("   Log file     : $l") }
			}
		)
	}
}

