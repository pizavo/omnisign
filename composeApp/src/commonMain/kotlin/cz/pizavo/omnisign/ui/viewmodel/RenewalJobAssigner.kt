package cz.pizavo.omnisign.ui.viewmodel

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.ui.model.RenewalJobOfferState

/**
 * Shared helper that builds [RenewalJobOfferState], checks existing job coverage,
 * and persists renewal job assignments.
 *
 * Used by both [SigningViewModel] and [TimestampViewModel] to avoid duplicating
 * the renewal job offer logic.
 *
 * @param configRepository Repository for reading and saving the application configuration.
 */
class RenewalJobAssigner(
	private val configRepository: ConfigRepository,
) {

	/**
	 * Build a [RenewalJobOfferState] for the given output file by reading the
	 * current [AppConfig] and checking whether any existing job already covers
	 * the file via glob matching.
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
		return Result.success(job.name)
	}

	companion object {

		/**
		 * Find a renewal job that already covers the given file.
		 *
		 * A job is considered covering when at least one of its glob patterns
		 * matches the file path. Buffer days and profile are intentionally not
		 * checked — coverage means the file **will be processed** by the job,
		 * regardless of which settings it uses.
		 *
		 * Path comparison is case-insensitive so that Windows drive-letter
		 * differences (e.g. `C:` vs `c:`) do not cause false negatives.
		 *
		 * @param filePath Absolute path to the file to check.
		 * @param jobs All currently configured renewal jobs.
		 * @return The first matching job, or `null` if no job covers the file.
		 */
		fun findCoveringJob(
			filePath: String,
			jobs: List<RenewalJob>,
		): RenewalJob? {
			val normalised = filePath.replace('\\', '/').lowercase()
			return jobs.firstOrNull { job ->
				job.globs.any { glob -> globMatchesFile(glob, normalised) }
			}
		}

		/**
		 * Check whether a glob pattern matches a file path using simplified
		 * glob semantics suitable for the coverage hint.
		 *
		 * Supports `*` (single directory segment), `**` (any depth), and `?`
		 * (single character). Literal paths (no wildcards) are compared directly.
		 *
		 * Both the glob and the file path are compared case-insensitively to
		 * handle Windows path casing differences.
		 *
		 * @param glob The glob pattern to test.
		 * @param filePath Absolute file path (any casing, any separator style).
		 * @return `true` if the glob matches the file path.
		 */
		internal fun globMatchesFile(glob: String, filePath: String): Boolean {
			val normGlob = glob.replace('\\', '/').lowercase()
			val normPath = filePath.replace('\\', '/').lowercase()
			if (!normGlob.contains('*') && !normGlob.contains('?') && !normGlob.contains('[')) {
				return normGlob == normPath
			}
			val regex = buildGlobRegex(normGlob)
			return regex.matches(normPath)
		}

		/**
		 * Convert a simplified glob pattern to a [Regex].
		 *
		 * @param glob Normalised glob pattern with forward slashes.
		 * @return Compiled regex that matches the glob semantics.
		 */
		private fun buildGlobRegex(glob: String): Regex {
			val sb = StringBuilder("^")
			var i = 0
			while (i < glob.length) {
				val c = glob[i]
				when {
					c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
						sb.append(".*")
						i += 2
						if (i < glob.length && glob[i] == '/') i++
						continue
					}
					c == '*' -> sb.append("[^/]*")
					c == '?' -> sb.append("[^/]")
					c in ".()[]{}+^$|" -> sb.append("\\$c")
					else -> sb.append(c)
				}
				i++
			}
			sb.append("$")
			return Regex(sb.toString())
		}
	}
}

