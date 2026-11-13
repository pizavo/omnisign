package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Validation-specific configuration.
 *
 * A `null` setting inherits the layer below: an operation override inherits the profile, and a
 * profile inherits the global configuration. [policyType], [checkRevocation] and [useEuLotl] fall
 * back to [ValidationPolicyType.DEFAULT_ETSI], `true` and `true` when the global configuration
 * leaves them unset too, so a [ResolvedConfig] always carries them. They are left out of the JSON
 * while unset, even by an encoder that writes defaults, so a client built before they could be
 * unset reads its own defaults instead of failing on a `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ValidationConfig(
	/**
	 * Validation policy type, or `null` to inherit it (see [ValidationConfig]).
	 */
	@EncodeDefault(EncodeDefault.Mode.NEVER)
	val policyType: ValidationPolicyType? = null,
	
	/**
	 * Path to custom validation policy file (when policyType is CUSTOM_FILE).
	 */
	val customPolicyPath: String? = null,
	
	/**
	 * Whether to check certificate revocation status, or `null` to inherit it (see
	 * [ValidationConfig]).
	 */
	@EncodeDefault(EncodeDefault.Mode.NEVER)
	val checkRevocation: Boolean? = null,
	
	/**
	 * Whether to use EU LOTL (List of Trusted Lists), or `null` to inherit it (see
	 * [ValidationConfig]).
	 */
	@EncodeDefault(EncodeDefault.Mode.NEVER)
	val useEuLotl: Boolean? = null,

	/**
	 * Whether to flag signatures whose trust anchor is not on the EU LOTL. When set, the
	 * validation UI marks each non-EU-LOTL signature with a crossed EU emblem. `null` inherits
	 * the higher layer (profile inherits global); resolved consumers treat null as `false`.
	 */
	val alertIfNotEuLotl: Boolean? = null,
	
	/**
	 * Custom trusted list sources registered for this validation context.
	 * Each entry may point to a remote URL or a local `file://` path.
	 */
	val customTrustedLists: List<CustomTrustedListConfig> = emptyList(),
	
	/**
	 * Declarative references to directly-trusted certificates, used by the server to provision its
	 * trust store from `signing.yml` at boot (see [TrustedCertificateRef]). Desktop and CLI manage
	 * trust through the app-managed [cz.pizavo.omnisign.domain.repository.TrustStore] and leave this
	 * empty; it is not consulted at validation time (trust is resolved from the store per scope).
	 */
	val trustedCertificates: List<TrustedCertificateRef> = emptyList(),
	
	/**
	 * Cryptographic algorithm constraint configuration.
	 * Controls how the validator reacts to expired algorithms and allows overriding
	 * the policy's reference update date.
	 */
	val algorithmConstraints: AlgorithmConstraintsConfig = AlgorithmConstraintsConfig(),

	/**
	 * Whether to allow **signing** with an expired signing certificate.
	 *
	 * When `true`, the signing certificate verifier's expired-certificate alert is disabled, so
	 * DSS's `SignatureRequirementsChecker` skips the check and a signature can be produced even
	 * though the certificate is past its `notAfter` date. This affects **signing only** —
	 * validation still reports expired certificates, and a signature created this way will fail
	 * validation (a B-level signature has no timestamp to anchor it before expiry). Intended for
	 * narrow cases (e.g. testing or a tolerated grace window).
	 *
	 * `null` inherits the higher layer (profile inherits global); resolved consumers treat null
	 * as `false` (do not allow).
	 */
	val allowExpiredCertificate: Boolean? = null,
)


