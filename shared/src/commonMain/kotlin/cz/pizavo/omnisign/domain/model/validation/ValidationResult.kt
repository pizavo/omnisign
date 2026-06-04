package cz.pizavo.omnisign.domain.model.validation

import kotlinx.serialization.Serializable

/**
 * Overall validation result.
 */
@Serializable
enum class ValidationResult {
    VALID,
    INVALID,
    INDETERMINATE
}

