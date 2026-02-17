package cz.pizavo.omnisign.domain.model.config.enums
import kotlinx.serialization.Serializable
/**
 * Validation policy source.
 */
@Serializable
enum class ValidationPolicyType {
    /**
     * Use default ETSI validation policy.
     */
    DEFAULT_ETSI,
    /**
     * Use custom validation policy from file.
     */
    CUSTOM_FILE
}
