package cz.pizavo.omnisign.domain.model.validation

import kotlinx.serialization.Serializable

/**
 * Validation indication as per ETSI standards.
 */
@Serializable
enum class ValidationIndication {
    TOTAL_PASSED,
    TOTAL_FAILED,
    INDETERMINATE
}

