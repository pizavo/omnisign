package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.RenewalAssessment
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ArchivingRepository.Companion.DEFAULT_RENEWAL_BUFFER_DAYS

/**
 * Use case for checking if a document needs archival renewal.
 */
class CheckArchivalRenewalUseCase(
	private val archivingRepository: ArchivingRepository
) {
	/**
	 * Assess what the document at [filePath] needs to reach or keep long-term protection.
	 *
	 * Delegates to [ArchivingRepository.needsArchivalRenewal], whose verdict is driven by the level
	 * the document is at: a document below B-LT needs revocation data before its signing certificate
	 * expires, a B-LT document needs sealing (or refreshing first), and only a B-LTA document is
	 * governed by the coverage-aware timestamp-aging rule.
	 *
	 * @param filePath Path to the PAdES document to inspect.
	 * @param renewalBufferDays Days before timestamp certificate expiry at which renewal is
	 *   triggered, for the B-LTA aging case. Defaults to [DEFAULT_RENEWAL_BUFFER_DAYS].
	 * @return The [RenewalAssessment] — what the document needs, why, and by when — or an error.
	 */
	suspend operator fun invoke(
		filePath: String,
		renewalBufferDays: Int = DEFAULT_RENEWAL_BUFFER_DAYS,
	): OperationResult<RenewalAssessment> =
		archivingRepository.needsArchivalRenewal(filePath, renewalBufferDays)
}



