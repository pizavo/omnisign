package cz.pizavo.omnisign.ui.viewmodel

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.SchedulerConfig
import cz.pizavo.omnisign.domain.port.SchedulerPort
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.ui.model.RenewalJobOfferState
import cz.pizavo.omnisign.ui.platform.renewalGlobSelects

/**
 * Shared helper that builds [RenewalJobOfferState], checks existing job coverage,
 * and persists renewal job assignments.
 *
 * Used by both [SigningViewModel] and [TimestampViewModel] to avoid duplicating
 * the renewal job offer logic.
 *
 * A job assigned here also brings the OS scheduler into existence when it is missing (see
 * [ensureSchedulerInstalled]). Without that, a user who only ever assigns jobs from the signing and
 * extension dialogs — the flow the offer is designed to encourage — accumulates renewal jobs that
 * nothing ever runs, because the only other place that installs the scheduler is saving the settings
 * dialog.
 *
 * @param configRepository Repository for reading and saving the application configuration.
 * @param schedulerPort Port for managing the OS-level daily renewal job; `null` on a platform that
 *   registers none, where assignment simply persists the job.
 * @param autoDetectedExecutablePath Absolute path of the running executable when it could be
 *   detected. `null` under `java -jar` and when run from a build tool, where
 *   [SchedulerConfig.cliExecutablePath] is the only path available.
 */
class RenewalJobAssigner(
	private val configRepository: ConfigRepository,
	private val schedulerPort: SchedulerPort? = null,
	private val autoDetectedExecutablePath: String? = null,
) {

	/**
	 * Build a [RenewalJobOfferState] for the given output file by reading the
	 * current [AppConfig] and checking whether any existing job already covers
	 * the file via glob matching. That check reads the file system (see
	 * [findCoveringJob]), so callers run this off the main thread.
	 *
	 * @param outputFile Absolute path to the B-LTA output file.
	 * @return A fully populated offer state ready for display.
	 */
	suspend fun buildOfferState(
		outputFile: String,
	): RenewalJobOfferState {
		val appConfig = configRepository.getCurrentConfig()
		val jobs = appConfig.renewalJobs.values.toList()
		val profiles = appConfig.profiles.keys.toList()
		val activeProfile = appConfig.activeProfile
		val covering = findCoveringJob(outputFile, jobs)
		return RenewalJobOfferState(
			outputFile = outputFile,
			existingJobs = jobs,
			availableProfiles = profiles,
			activeProfile = activeProfile,
			coveringJob = covering,
		)
	}

	/**
	 * Add the output file as a literal glob to an existing renewal job and persist.
	 *
	 * @param jobName Name of the existing job.
	 * @param outputFile Absolute path to the file to add.
	 * @return The name of the assigned job, or `null` if the job was not found.
	 */
	suspend fun assignToExistingJob(jobName: String, outputFile: String): String? {
		val appConfig = configRepository.getCurrentConfig()
		val job = appConfig.renewalJobs[jobName] ?: return null
		val updatedJob = job.copy(globs = job.globs + outputFile)
		val updatedJobs = appConfig.renewalJobs + (jobName to updatedJob)
		configRepository.saveConfig(appConfig.copy(renewalJobs = updatedJobs))
		ensureSchedulerInstalled(appConfig.schedulerConfig)
		return jobName
	}

	/**
	 * Create a new renewal job with the output file as the initial glob and persist.
	 *
	 * @param job The new renewal job to create (must include the output file in [RenewalJob.globs]).
	 * @return The name of the created job, or an error message if a job with that name already exists.
	 */
	suspend fun createNewJob(job: RenewalJob): Result<String> {
		val appConfig = configRepository.getCurrentConfig()
		if (appConfig.renewalJobs.containsKey(job.name)) {
			return Result.failure(IllegalArgumentException("A renewal job named '${job.name}' already exists."))
		}
		val updatedJobs = appConfig.renewalJobs + (job.name to job)
		configRepository.saveConfig(appConfig.copy(renewalJobs = updatedJobs))
		ensureSchedulerInstalled(appConfig.schedulerConfig)
		return Result.success(job.name)
	}

	/**
	 * Register the OS-level daily renewal job when it is not registered already.
	 *
	 * Only ever *installs*. Assignment adds a job and never removes one, so the condition that would
	 * justify an uninstall — the last job being deleted — cannot arise here; that direction stays
	 * with the settings dialog, which reconciles both ways against the whole configuration.
	 *
	 * An already-registered scheduler is left untouched rather than re-registered, so repeated
	 * assignments do not rewrite the OS entry. Its schedule and log file therefore keep whatever the
	 * settings dialog last set, which is where they are owned.
	 *
	 * Skipped entirely when no executable path can be determined — the case that arises under
	 * `java -jar` and when running from a build tool. Installing would need a path to invoke daily and
	 * there is none, so nothing is registered and the settings dialog goes on reporting the scheduler
	 * as not installed, which is the truth.
	 *
	 * A failure is deliberately swallowed: the job has already been persisted and is not worth undoing
	 * over a scheduler that could not be registered, the settings dialog reports the real status
	 * either way, and saving it reconciles. This mirrors how [SettingsViewModel] treats its own
	 * uninstall call.
	 *
	 * @param schedulerConfig The persisted scheduler settings, for the fallback executable path and
	 *   the schedule to register.
	 */
	@Suppress("SwallowedException")
	private fun ensureSchedulerInstalled(schedulerConfig: SchedulerConfig) {
		val port = schedulerPort ?: return
		val executablePath = autoDetectedExecutablePath
			?: schedulerConfig.cliExecutablePath?.trim()?.ifBlank { null }
			?: return
		try {
			if (port.isInstalled()) return
			port.install(
				cliExecutablePath = executablePath,
				runAtHour = schedulerConfig.runAtHour,
				runAtMinute = schedulerConfig.runAtMinute,
				logFilePath = schedulerConfig.logFilePath?.trim()?.ifBlank { null },
			)
		} catch (_: Exception) {
		}
	}

	companion object {

		/**
		 * Find a renewal job that already covers the given file.
		 *
		 * A job is considered covering when at least one of its glob patterns
		 * selects the file the way a renewal run selects the files it renews
		 * ([renewalGlobSelects]), so the offer is skipped only for a file the job's
		 * runs will pick up. Buffer days and profile are intentionally not
		 * checked — coverage means the file **will be processed** by the job,
		 * regardless of which settings it uses.
		 *
		 * Paths are compared as the run reaches them, through the file system:
		 * letter case is ignored on Windows, so that drive-letter differences
		 * (e.g. `C:` vs `c:`) do not cause false negatives, and counts where the
		 * run tells `SCAN.PDF` from `scan.pdf`. As the check reads the file
		 * system, callers run it off the main thread.
		 *
		 * @param filePath Absolute path to the file to check.
		 * @param jobs All currently configured renewal jobs.
		 * @return The first matching job, or `null` if no job covers the file.
		 */
		fun findCoveringJob(
			filePath: String,
			jobs: List<RenewalJob>,
		): RenewalJob? = jobs.firstOrNull { job ->
			job.globs.any { glob -> renewalGlobSelects(glob, filePath) }
		}
	}
}

