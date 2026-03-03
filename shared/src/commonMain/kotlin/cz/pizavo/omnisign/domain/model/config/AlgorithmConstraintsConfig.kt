package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig.Companion.DEFAULT
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Configuration for cryptographic algorithm constraint enforcement during signing
 * pre-checks and validation.
 *
 * **Null means inherit.** All severity fields are nullable so that a profile or
 * operation override can set only the fields it cares about and let the rest fall
 * through to the layer below (operation → profile → global → [DEFAULT]).
 * This mirrors how every other profile-overridable field works in the config model.
 *
 * **Defaults**
 *
 * [DEFAULT] holds the canonical built-in values matching the DSS ETSI 6.3 policy.
 * It is the terminal fallback in [ResolvedConfig] resolution and the reference for
 * displaying "default" vs "overridden" in the UI.
 *
 * **Signing pre-check vs. validation**
 *
 * These constraints serve two independent purposes:
 * - **Pre-signing** — [cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker]
 *   reads the *resolved* config in `commonMain` before any DSS call is made.
 *   DSS itself is not involved; this check works on all platforms.
 * - **Validation (JVM)** — `AdESPolicy` patches the DSS `ValidationPolicy` JAXB
 *   object so DSS uses the same dates and levels when evaluating existing signatures.
 *
 * **Policy update date**
 *
 * [policyUpdateDate] is managed automatically — stamped with the current date whenever
 * any constraint is changed via [stampedToday].  When null the DSS built-in default
 * (`2024-10-13` in DSS 6.3) is used.
 *
 * @property expirationLevel Severity when an algorithm expired **before or on**
 *   [policyUpdateDate].  Null means inherit from the layer below.
 * @property expirationLevelAfterUpdate Severity when an algorithm expired **after**
 *   [policyUpdateDate].  Null means inherit from the layer below.
 * @property policyUpdateDate ISO-8601 date string stamped automatically on every change.
 *   Null defers to the DSS built-in default.
 * @property expirationDateOverrides Per-algorithm expiration date overrides
 *   (algorithm name → ISO-8601 date).  Merged additively across layers during resolution.
 */
@Serializable
data class AlgorithmConstraintsConfig(
	val expirationLevel: AlgorithmConstraintLevel? = null,
	val expirationLevelAfterUpdate: AlgorithmConstraintLevel? = null,
	val policyUpdateDate: String? = null,
	val expirationDateOverrides: Map<String, String> = emptyMap()
) {
	/**
	 * Return a copy of this config with [policyUpdateDate] stamped to [today].
	 *
	 * Must be called whenever any algorithm constraint is changed so that the DSS
	 * validation policy and the pre-signing check can correctly distinguish algorithms
	 * that expired "recently" from those that expired "long ago".
	 */
	fun stampedToday(today: LocalDate): AlgorithmConstraintsConfig =
		copy(policyUpdateDate = today.toString())
	
	companion object {
		/**
		 * Canonical built-in defaults matching the DSS ETSI 6.3 policy behaviour.
		 *
		 * Used as the terminal fallback during config layer resolution and as the
		 * reference for displaying "default" vs "overridden" in the UI.
		 */
		val DEFAULT = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN,
			policyUpdateDate = null,
			expirationDateOverrides = emptyMap()
		)
	}
}
