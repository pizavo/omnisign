package cz.pizavo.omnisign.domain.service

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker.Companion.DEFAULT_UPDATE_DATE
import kotlinx.datetime.LocalDate

/**
 * Outcome of an algorithm expiration check for a signing operation.
 */
enum class AlgorithmStatus {
	/** The algorithm is within its validity period. */
	VALID,
	
	/**
	 * The algorithm's expiration date has passed but the effective severity is not
	 * [AlgorithmConstraintLevel.FAIL].  Signing may proceed; a warning should be shown.
	 */
	EXPIRED_WARN,
	
	/**
	 * The algorithm's expiration date has passed and the effective severity is
	 * [AlgorithmConstraintLevel.FAIL].  Signing must be blocked.
	 */
	EXPIRED_FAIL,
}

/**
 * Evaluates whether a [HashAlgorithm] is acceptable for use in a new signing operation
 * on the given [today] date, according to [AlgorithmConstraintsConfig].
 *
 * Mirrors the DSS `AlgoExpirationDate` evaluation logic exactly:
 *
 * 1. Expiry resolution: [AlgorithmConstraintsConfig.expirationDateOverrides] first, then
 *    [HashAlgorithm.expirationDate].  No expiry date → always [AlgorithmStatus.VALID].
 * 2. If today is on or before the effective expiry date → [AlgorithmStatus.VALID].
 * 3. The effective update date is [AlgorithmConstraintsConfig.policyUpdateDate] parsed
 *    as ISO-8601, or the DSS 6.3 default [DEFAULT_UPDATE_DATE] when null.
 * 4. If the algorithm's expiry date is **after** the effective update date, the algorithm was
 *    not yet known to be expired when the policy was last updated, so the more lenient
 *    [AlgorithmConstraintsConfig.expirationLevelAfterUpdate] determines the outcome.
 * 5. Otherwise (expired before or on the update date) the algorithm was already known to be
 *    broken when the policy was written, so the stricter
 *    [AlgorithmConstraintsConfig.expirationLevel] determines the outcome.
 *
 * In both cases [AlgorithmConstraintLevel.FAIL] maps to [AlgorithmStatus.EXPIRED_FAIL];
 * any other level maps to [AlgorithmStatus.EXPIRED_WARN].
 */
class AlgorithmExpirationChecker {
	
	companion object {
		/**
		 * The DSS 6.3 default `UpdateDate` used when
		 * [AlgorithmConstraintsConfig.policyUpdateDate] is null.
		 */
		val DEFAULT_UPDATE_DATE: LocalDate = LocalDate(2024, 10, 13)
	}
	
	/**
	 * Return the [AlgorithmStatus] for [algorithm] on [today] using [config].
	 *
	 * Consults [AlgorithmConstraintsConfig.expirationDateOverrides] first so that user- or
	 * app-defined dates take precedence over the bundled DSS snapshot in
	 * [HashAlgorithm.expirationDate].
	 */
	@Suppress("ReturnCount")
	fun check(
		algorithm: HashAlgorithm,
		config: AlgorithmConstraintsConfig,
		today: LocalDate,
	): AlgorithmStatus {
		val expiry = effectiveExpirationDate(algorithm, config) ?: return AlgorithmStatus.VALID
		if (today <= expiry) return AlgorithmStatus.VALID
		val updateDate = config.policyUpdateDate
			?.let { LocalDate.parse(it) }
			?: DEFAULT_UPDATE_DATE
		val level = if (expiry > updateDate)
			config.expirationLevelAfterUpdate ?: AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate!!
		else
			config.expirationLevel ?: AlgorithmConstraintsConfig.DEFAULT.expirationLevel!!
		return if (level == AlgorithmConstraintLevel.FAIL) AlgorithmStatus.EXPIRED_FAIL
		else AlgorithmStatus.EXPIRED_WARN
	}
	
	/**
	 * Return the effective expiration date for [algorithm], consulting
	 * [AlgorithmConstraintsConfig.expirationDateOverrides] before the bundled
	 * [HashAlgorithm.expirationDate] snapshot.
	 */
	fun effectiveExpirationDate(
		algorithm: HashAlgorithm,
		config: AlgorithmConstraintsConfig,
	): LocalDate? = config.expirationDateOverrides[algorithm.name]
		?.let { LocalDate.parse(it) }
		?: algorithm.expirationDate
	
	/**
	 * Return a human-readable expiration warning message for [algorithm], using the
	 * effective expiration date from [config] if an override is present.
	 */
	fun warningMessage(algorithm: HashAlgorithm, config: AlgorithmConstraintsConfig): String {
		val date = effectiveExpirationDate(algorithm, config)
		return "Algorithm ${algorithm.name} expired on $date per ETSI TS 119 312. " +
				"Signatures created with this algorithm may fail validation."
	}
}

