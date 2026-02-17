package cz.pizavo.omnisign.domain.model.config.enums

import kotlinx.serialization.Serializable

/**
 * PAdES signature levels.
 */
@Serializable
enum class SignatureLevel {
    /**
     * Basic Electronic Signature (B-B).
     */
    PADES_BASELINE_B,
    
    /**
     * Electronic Signature with Time (B-T).
     */
    PADES_BASELINE_T,
    
    /**
     * Electronic Signature with Long-Term Validation Material (B-LT).
     */
    PADES_BASELINE_LT,
    
    /**
     * Electronic Signature for Long-Term Archival (B-LTA).
     */
    PADES_BASELINE_LTA;
    
    /**
     * Get the DSS signature level name.
     */
    val dssName: String
        get() = when (this) {
            PADES_BASELINE_B -> "PAdES-BASELINE-B"
            PADES_BASELINE_T -> "PAdES-BASELINE-T"
            PADES_BASELINE_LT -> "PAdES-BASELINE-LT"
            PADES_BASELINE_LTA -> "PAdES-BASELINE-LTA"
        }
}

