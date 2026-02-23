package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

/**
 * Parameters for extending an already-signed PDF to a higher PAdES level.
 *
 * @property inputFile Absolute path to the signed PDF to extend.
 * @property outputFile Absolute path for the extended output file.
 * @property targetLevel The PAdES level to extend to — must be higher than the current document
 *   level.  Use [SignatureLevel.PADES_BASELINE_T] to add a timestamp to a B-B document,
 *   [SignatureLevel.PADES_BASELINE_LT] to embed revocation data, or
 *   [SignatureLevel.PADES_BASELINE_LTA] for a full archival timestamp.
 * @property resolvedConfig Pre-resolved configuration; falls back to the active config when null.
 */
data class ArchivingParameters(
    val inputFile: String,
    val outputFile: String,
    val targetLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_LTA,
    val resolvedConfig: ResolvedConfig? = null
)
