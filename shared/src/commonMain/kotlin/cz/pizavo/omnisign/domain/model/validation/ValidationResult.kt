package cz.pizavo.omnisign.domain.model.validation

import kotlinx.serialization.Serializable

/**
 * Overall validation result.
 */
@Serializable
enum class ValidationResult {
    /** Every signature passed, and none rests on an anchor its scope trusts only for timestamping. */
    VALID,

    /** At least one signature failed. */
    INVALID,

    /** No signature failed, but at least one could not be confirmed as valid. */
    INDETERMINATE,

    /**
     * The document has no signatures, so there is nothing to call valid or invalid. Document
     * timestamps alone do not count as signatures.
     */
    NO_SIGNATURES,
}
