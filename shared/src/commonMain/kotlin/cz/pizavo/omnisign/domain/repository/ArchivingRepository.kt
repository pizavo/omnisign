package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository.Companion.DEFAULT_RENEWAL_BUFFER_DAYS

/**
 * Repository for post-signing document extension and archival renewal.
 */
interface ArchivingRepository {
	
	/**
	 * Extend an already-signed PDF to a higher PAdES level.
	 *
	 * Covers all promotion paths:
	 * - B-B → B-T (add RFC 3161 timestamp)
	 * - B-T → B-LT (embed CRL/OCSP revocation data)
	 * - B-LT → B-LTA (add archival document timestamp)
	 * - B-LTA → B-LTA (archival renewal — re-timestamp before expiry)
	 *
	 * A TSA endpoint must be configured in [ArchivingParameters.resolvedConfig] for any
	 * target level of B-T or above.
	 *
	 * @param parameters Extension parameters including the target level.
	 * @return Result with the output path and applied level, or an [cz.pizavo.omnisign.domain.model.error.ArchivingError].
	 */
	suspend fun extendDocument(parameters: ArchivingParameters): OperationResult<ArchivingResult>
	
	/**
	 * Check whether [filePath] should be re-timestamped to keep its archival protection current.
	 *
	 * The decision is **coverage-aware**: renewal is needed when a timestamp that no current
	 * document timestamp seals is approaching the expiry of its signing (TSA) certificate — either
	 * the outermost document timestamp itself (the B-LTA seal), the signature timestamp of a B-LT
	 * document with no seal yet, or a signature timestamp applied after the last archival timestamp.
	 * Timestamps already covered by a still-valid document timestamp are ignored, so a document is
	 * not re-timestamped on every scheduler run once one of its inner timestamps ages.
	 *
	 * @param filePath Absolute path to the PAdES document to inspect.
	 * @param renewalBufferDays Number of days before a timestamp certificate's expiry at which
	 *   renewal is considered necessary. Defaults to [DEFAULT_RENEWAL_BUFFER_DAYS].
	 * @return True if an uncovered timestamp's signing certificate expires within the renewal window.
	 */
	suspend fun needsArchivalRenewal(
		filePath: String,
		renewalBufferDays: Int = DEFAULT_RENEWAL_BUFFER_DAYS,
	): OperationResult<Boolean>
	
	/**
	 * Perform a lightweight check of the document to determine its current timestamp
	 * and signature level state.
	 *
	 * This is a fast operation that does not fetch CRL/OCSP data or load trusted lists.
	 *
	 * @param inputBytes Raw bytes of the PDF document to inspect.
	 * @return A [DocumentTimestampInfo] summarising the document state, or an error.
	 */
	suspend fun getDocumentTimestampInfo(inputBytes: ByteArray): OperationResult<DocumentTimestampInfo>
	
	companion object {
		/** Default number of days before expiry at which archival renewal is triggered. */
		const val DEFAULT_RENEWAL_BUFFER_DAYS: Int = 90
	}
}
