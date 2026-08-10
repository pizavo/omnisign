package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.RenewalAssessment
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.model.result.RenewalReason
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
	 * Assess what [filePath] needs to keep — or to reach — long-term protection.
	 *
	 * The decision is driven by the level the document is actually at, because each level is missing
	 * something different and each gap has its own deadline:
	 *
	 * - **Below B-LT** — no usable revocation data. It must be embedded before the *signing*
	 *   certificate expires, after which no acceptable revocation data for it can be obtained ever
	 *   again. Reported as [RenewalReason.BELOW_LT] straight away rather than at some timestamp's
	 *   expiry, or as [RenewalNeed.UNRECOVERABLE] when that deadline has already passed.
	 * - **B-LT** — revocation data is present but nothing proves when it existed. It is sealed with
	 *   an archival timestamp ([RenewalReason.LT_NOT_SEALED]) before the earlier of its `nextUpdate`
	 *   and its issuer's expiry, or refreshed first ([RenewalReason.LT_REFRESH_NEEDED]) when it
	 *   predates the signature timestamp and so covers nothing.
	 * - **B-LTA** — protection is complete and merely ages. Here, and only here, the
	 *   **coverage-aware** rule applies: renewal is due when a timestamp that no current document
	 *   timestamp seals approaches its signing (TSA) certificate's expiry
	 *   ([RenewalReason.TIMESTAMP_EXPIRING]) or its algorithms age out
	 *   ([RenewalReason.ALGORITHM_WEAKENING]). Timestamps already covered by a current document
	 *   timestamp are ignored, so a document is not re-timestamped on every run once an inner
	 *   timestamp ages.
	 *
	 * When a renewal-relevant timestamp's signing certificate cannot be resolved — so its expiry is
	 * unknown — the status cannot be determined and a
	 * [cz.pizavo.omnisign.domain.model.error.ArchivingError.RenewalStatusUndeterminable] is returned
	 * rather than silently treating the document as not needing renewal.
	 *
	 * @param filePath Absolute path to the PAdES document to inspect.
	 * @param renewalBufferDays Number of days before a timestamp certificate's expiry at which
	 *   renewal is considered necessary. Defaults to [DEFAULT_RENEWAL_BUFFER_DAYS]. It applies to the
	 *   B-LTA aging case only: the lower levels are missing material that is available now and will
	 *   not be later, so there is nothing to wait for.
	 * @return The [RenewalAssessment] — what the document needs, why, and by when — or an
	 *   [cz.pizavo.omnisign.domain.model.error.ArchivingError].
	 */
	suspend fun needsArchivalRenewal(
		filePath: String,
		renewalBufferDays: Int = DEFAULT_RENEWAL_BUFFER_DAYS,
	): OperationResult<RenewalAssessment>
	
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
