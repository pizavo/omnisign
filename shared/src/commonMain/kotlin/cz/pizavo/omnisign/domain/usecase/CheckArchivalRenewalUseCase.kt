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
	 * Delegates to [ArchivingRepository.needsArchivalRenewal], whose coverage-aware rule re-times
	 * only the outermost document timestamp and any signature timestamp not sealed by it — never a
	 * timestamp a current document timestamp already covers.
	 *
	 * @param filePath Path to the PAdES document to inspect.
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



