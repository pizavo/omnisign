package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import kotlinx.serialization.Serializable

/**
 * Validation-specific configuration.
 */
@Serializable
data class ValidationConfig(
	/**
	 * Validation policy type.
	 */
	val policyType: ValidationPolicyType = ValidationPolicyType.DEFAULT_ETSI,
	
	/**
	 * Path to custom validation policy file (when policyType is CUSTOM_FILE).
	 */
	val customPolicyPath: String? = null,
	
	/**
	 * Whether to check certificate revocation status.
	 */
	val checkRevocation: Boolean = true,
	
	/**
	 * Whether to use EU LOTL (List of Trusted Lists).
	 */
	val useEuLotl: Boolean = true,
	
	/**
	 * Custom trusted list sources registered for this validation context.
	 * Each entry may point to a remote URL or a local `file://` path.
	 */
	val customTrustedLists: List<CustomTrustedListConfig> = emptyList(),
	
	/**
	 * Directly trusted certificates stored inline (Base64-encoded DER).
	 * These are wired into DSS as a [CommonTrustedCertificateSource] alongside
	 * any trusted lists, without requiring an ETSI TS 119612 XML document.
	 */
	val trustedCertificates: List<TrustedCertificateConfig> = emptyList(),
	
	/**
	 * Cryptographic algorithm constraint configuration.
	 * Controls how the validator reacts to expired algorithms and allows overriding
	 * the policy's reference update date.
	 */
	val algorithmConstraints: AlgorithmConstraintsConfig = AlgorithmConstraintsConfig()
)


