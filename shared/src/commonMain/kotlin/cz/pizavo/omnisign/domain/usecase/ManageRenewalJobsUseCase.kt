package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository

/**
 * Use case for managing named [RenewalJob] entries stored in [cz.pizavo.omnisign.domain.model.config.AppConfig].
 */
class ManageRenewalJobsUseCase(
	private val configRepository: ConfigRepository,
) {
	/**
	 * Insert or replace a renewal job.
	 *
	 * @param job The job to add or update.
	 * @return Unit on success or a [ConfigurationError].
	 */
	suspend fun upsert(job: RenewalJob): OperationResult<Unit> {
		val current = configRepository.getCurrentConfig()
		val updated = current.copy(renewalJobs = current.renewalJobs + (job.name to job))
		return configRepository.saveConfig(updated)
	}
	
	/**
	 * Remove a renewal job by name.
	 *
	 * @param name The job name to remove.
	 * @return Unit on success, or [ConfigurationError.InvalidConfiguration] if the job does not exist.
	 */
	suspend fun remove(name: String): OperationResult<Unit> {
		val current = configRepository.getCurrentConfig()
		if (!current.renewalJobs.containsKey(name)) {
			return ConfigurationError.InvalidConfiguration(
				message = "Renewal job '$name' does not exist"
			).left()
		}
		val updated = current.copy(renewalJobs = current.renewalJobs - name)
		return configRepository.saveConfig(updated)
	}
	
	/**
	 * Retrieve a single renewal job by name.
	 *
	 * @param name The job name to look up.
	 * @return The [RenewalJob] on success or [ConfigurationError.InvalidConfiguration] if not found.
	 */
	suspend fun get(name: String): OperationResult<RenewalJob> {
		val current = configRepository.getCurrentConfig()
		return current.renewalJobs[name]?.right()
			?: ConfigurationError.InvalidConfiguration(
				message = "Renewal job '$name' does not exist"
			).left()
	}
	
	/**
	 * List all configured renewal jobs.
	 *
	 * @return Map of job name to [RenewalJob].
	 */
	suspend fun list(): OperationResult<Map<String, RenewalJob>> {
		val current = configRepository.getCurrentConfig()
		return current.renewalJobs.right()
	}
}

