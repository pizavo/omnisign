package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ArchivingRepository.Companion.DEFAULT_RENEWAL_BUFFER_DAYS

/**
 * Use case for checking if a document needs archival renewal.
 */
class CheckArchivalRenewalUseCase(
	private val archivingRepository: ArchivingRepository
) {
	/**
	 * Check if the document at [filePath] needs re-timestamping.
	 *
	 * @param filePath Path to the B-LTA PDF to inspect.
	 * @param renewalBufferDays Days before timestamp certificate expiry at which renewal is
	 *   triggered. Defaults to [DEFAULT_RENEWAL_BUFFER_DAYS].
	 * @return True if renewal is needed, or an error.
	 */
	suspend operator fun invoke(
		filePath: String,
		renewalBufferDays: Int = DEFAULT_RENEWAL_BUFFER_DAYS,
	): OperationResult<Boolean> =
		archivingRepository.needsArchivalRenewal(filePath, renewalBufferDays)
}



