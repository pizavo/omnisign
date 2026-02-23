package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.OperationResult

/**
 * Repository for post-signing document extension and archival renewal.
 */
interface ArchivingRepository {

    /**
     * Extend an already-signed PDF to a higher PAdES level by calling DSS
     * [eu.europa.esig.dss.pades.signature.PAdESService.extendDocument].
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
     * Check whether the archival timestamps in [filePath] are close to expiry and the
     * document should be re-timestamped.
     *
     * @param filePath Absolute path to the B-LTA document to inspect.
     * @return True if any timestamp signing certificate expires within the renewal window.
     */
    suspend fun needsArchivalRenewal(filePath: String): OperationResult<Boolean>
}
