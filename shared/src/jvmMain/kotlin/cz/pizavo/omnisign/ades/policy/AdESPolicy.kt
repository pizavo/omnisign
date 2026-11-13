package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm as DssEncryptionAlgorithm
import eu.europa.esig.dss.enumerations.SubContext
import eu.europa.esig.dss.model.policy.CryptographicSuite
import eu.europa.esig.dss.model.policy.ValidationPolicy
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import eu.europa.esig.dss.policy.EtsiValidationPolicyFactory
import eu.europa.esig.dss.policy.ValidationPolicyFacade
import eu.europa.esig.dss.policy.jaxb.Algo
import eu.europa.esig.dss.policy.jaxb.AlgoExpirationDate
import eu.europa.esig.dss.policy.jaxb.CryptographicConstraint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Loads and optionally customizes a DSS [ValidationPolicy].
 *
 * When [AlgorithmConstraintsConfig] is supplied the loaded ETSI policy's
 * `AlgoExpirationDate` constraint is patched in-place so the caller-supplied
 * severity levels, optional update-date override, and per-algorithm date overrides are
 * applied before validation.
 *
 * When disabled algorithm sets are supplied, the matching entries are removed from
 * every `AcceptableDigestAlgo` and `AcceptableEncryptionAlgo` list in the JAXB
 * policy tree so that DSS itself reports them as non-compliant during validation.
 */
open class AdESPolicy {
	
	/**
	 * Load a [ValidationPolicy] from [policyFile] (or the DSS built-in ETSI default when
	 * null / non-existent), apply [algorithmConstraints] on top, and strip any
	 * [disabledHashAlgorithms] / [disabledEncryptionAlgorithms] from every
	 * `AcceptableDigestAlgo` / `AcceptableEncryptionAlgo` section of the policy.
	 */
	open fun load(
		policyFile: File?,
		algorithmConstraints: AlgorithmConstraintsConfig? = null,
		disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
		disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	): ValidationPolicy {
		val facade = ValidationPolicyFacade.newFacade()
		val policy = when {
			policyFile?.exists() == true -> facade.getValidationPolicy(policyFile)
			else -> EtsiValidationPolicyFactory().loadDefaultValidationPolicy()
		}
		if (algorithmConstraints != null) {
			applyAlgorithmConstraints(policy, algorithmConstraints)
		}
		if (disabledHashAlgorithms.isNotEmpty() || disabledEncryptionAlgorithms.isNotEmpty()) {
			removeDisabledAlgorithms(policy, disabledHashAlgorithms, disabledEncryptionAlgorithms)
		}
		return policy
	}

	/**
	 * Build the [CryptographicSuite] DSS applies to signatures — loading the default ETSI policy
	 * with [algorithmConstraints] patched in — so callers can query algorithm acceptability and
	 * expiration dates (e.g. to decide whether a timestamp's algorithms have aged out and the
	 * document needs archival renewal).
	 *
	 * @param algorithmConstraints Optional overrides to apply; when null the bundled ETSI
	 *   cryptographic schedule is used as-is.
	 * @return the signature-context cryptographic suite, or null when the loaded policy does not
	 *   expose one.
	 */
	open fun cryptographicSuite(algorithmConstraints: AlgorithmConstraintsConfig? = null): CryptographicSuite? {
		val policy = load(policyFile = null, algorithmConstraints = algorithmConstraints)
		if (policy !is EtsiValidationPolicy) return null
		return runCatching { policy.getSignatureCryptographicConstraint(Context.SIGNATURE) }.getOrNull()
	}
	
	/**
	 * Patch the algorithm expiration levels on every [CryptographicSuite] section exposed
	 * by [policy] via the public [CryptographicSuite] interface.
	 *
	 * Also applies [AlgorithmConstraintsConfig.policyUpdateDate] and
	 * [AlgorithmConstraintsConfig.expirationDateOverrides] directly on the JAXB
	 * `AlgoExpirationDate` object so that DSS validation honors the same dates as the
	 * pre-signing check. How each override reaches the `Algo` entries is described at
	 * [applyPerAlgorithmDateOverrides].
	 */
	private fun applyAlgorithmConstraints(
		policy: ValidationPolicy,
		config: AlgorithmConstraintsConfig
	) {
		if (policy !is EtsiValidationPolicy) return
		
		val dssLevel = (config.expirationLevel ?: AlgorithmConstraintsConfig.DEFAULT.expirationLevel!!).toDss()
		val dssLevelAfterUpdate = (config.expirationLevelAfterUpdate
			?: AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate!!).toDss()
		
		collectCryptographicSuites(policy).forEach { suite ->
			suite.algorithmsExpirationDateLevel = dssLevel
			suite.setAlgorithmsExpirationTimeAfterPolicyUpdateLevel(dssLevelAfterUpdate)
		}
		
		val algoExpirationDate = policy.cryptographic?.algoExpirationDate ?: return
		
		config.policyUpdateDate?.let { algoExpirationDate.updateDate = it }
		
		if (config.expirationDateOverrides.isNotEmpty()) {
			applyPerAlgorithmDateOverrides(algoExpirationDate, config.expirationDateOverrides)
		}
	}
	
	/**
	 * Apply [overrides] to the `Algo` entries of [expirations]. Their list is the live JAXB one,
	 * backed by JAXB's lazy-init `ArrayList`, so the changes reach the DSS policy evaluation.
	 *
	 * DSS grades an encryption algorithm by key size: an entry with a `Size` applies to every key
	 * of at least that size, and a key stays acceptable until the latest date among the entries
	 * that apply to it. An override key therefore takes one of two forms:
	 * - `NAME:SIZE`, such as `RSA:1900`, sets the date of that size tier, adding the tier when
	 *   the policy has none.
	 * - `NAME` sets the date of an algorithm the policy does not grade by size, such as a hash
	 *   algorithm, adding the algorithm when the policy lacks it. For an algorithm graded by size
	 *   it only caps every tier: a tier dated later, or not dated at all, takes the date, and no
	 *   tier's date moves later. Setting one tier instead would carry its date to every larger key,
	 *   accepting weak keys again or leaving strong ones untouched.
	 *
	 * The `NAME:SIZE` keys are applied first, so a cap also bounds the tiers they set or add,
	 * whatever order [overrides] lists them in.
	 *
	 * A name finds the entries DSS reads as the same algorithm ([isFor]), so `PLAIN_ECDSA` and `EC`
	 * find the `PLAIN-ECDSA` and `ECDSA` tiers, and an added entry takes DSS's own spelling
	 * ([policySpelling]). Matching the spelling alone would add an entry without a `Size` for such a
	 * name, and DSS applies that entry to keys of every size.
	 *
	 * A `NAME:SIZE` key whose size is not a positive number is skipped, and so is a cap whose date
	 * the policy's date format cannot read, since DSS would read such a date as no expiry at all.
	 */
	private fun applyPerAlgorithmDateOverrides(
		expirations: AlgoExpirationDate,
		overrides: Map<String, String>,
	) {
		val algos = expirations.algos
		val (sized, named) = overrides.entries.partition { ':' in it.key }
		sized.forEach { (key, date) ->
			key.substringAfter(':').toIntOrNull()?.takeIf { it > 0 }?.let { size ->
				setExpirationDate(algos, key.substringBefore(':'), size, date)
			}
		}
		named.forEach { (name, date) ->
			val entries = algos.filter { it.isFor(name) }
			if (entries.any { it.size != null }) {
				capExpirationDates(entries, date, dssDateFormat(expirations))
			} else {
				setExpirationDate(algos, name, null, date)
			}
		}
	}
	
	/**
	 * Set the date of the entry in [algos] for the algorithm [name] with key size [size], `null` for
	 * an entry without one, adding the entry when the list has none.
	 */
	private fun setExpirationDate(algos: MutableList<Algo>, name: String, size: Int?, date: String) {
		val existing = algos.find { it.isFor(name) && it.size == size }
		if (existing != null) {
			existing.date = date
		} else {
			algos += Algo().also { it.value = policySpelling(name); it.size = size; it.date = date }
		}
	}
	
	/**
	 * Whether this entry is for the algorithm [name] stands for, as DSS reads both. An encryption
	 * algorithm matches under every spelling DSS reads as that algorithm, such as `PLAIN-ECDSA` and
	 * `PLAIN_ECDSA`, or `ECDSA` and `EC`. Any other name, a digest algorithm's included, matches only
	 * as spelled, which is the one spelling DSS reads it by.
	 */
	private fun Algo.isFor(name: String): Boolean {
		val algorithm = dssEncryptionAlgorithm(name) ?: return value == name
		return value?.let { dssEncryptionAlgorithm(it) } == algorithm
	}
	
	/**
	 * The spelling an entry added for [name] takes: DSS's own name for the encryption algorithm
	 * [name] stands for, the one the bundled policy writes, or [name] itself for any other name.
	 */
	private fun policySpelling(name: String): String = dssEncryptionAlgorithm(name)?.getName() ?: name
	
	/** The encryption algorithm DSS reads [name] as, or `null` when DSS reads it as none. */
	private fun dssEncryptionAlgorithm(name: String): DssEncryptionAlgorithm? =
		runCatching { DssEncryptionAlgorithm.forName(name) }.getOrNull()
	
	/**
	 * Give [date] to every entry in [entries] that ends after it or never ends, so no entry ends
	 * later than [date]. Dates are compared as DSS reads them, through [format]; nothing changes
	 * when [format] cannot read [date].
	 */
	private fun capExpirationDates(entries: List<Algo>, date: String, format: SimpleDateFormat) {
		val cap = format.parseOrNull(date) ?: return
		entries.forEach { algo ->
			val current = format.parseOrNull(algo.date)
			if (current == null || current.after(cap)) algo.date = date
		}
	}
	
	/**
	 * The date format DSS reads the dates of [expirations] with: the policy's own `Format`, or
	 * [DSS_DEFAULT_DATE_FORMAT] without one, in UTC, as in DSS's `CryptographicConstraintWrapper`.
	 * It is a [SimpleDateFormat] because that is what DSS parses these dates with.
	 */
	private fun dssDateFormat(expirations: AlgoExpirationDate): SimpleDateFormat =
		SimpleDateFormat(expirations.format ?: DSS_DEFAULT_DATE_FORMAT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
	
	/**
	 * Parse [value] the way DSS does, or return `null` when it is absent or unreadable, which DSS
	 * treats as no expiry.
	 */
	private fun SimpleDateFormat.parseOrNull(value: String?): Date? =
		value?.let { runCatching { parse(it) }.getOrNull() }
	
	/**
	 * Collect all non-null [CryptographicSuite] instances from the standard DSS
	 * policy sections that are relevant for PAdES validation.
	 */
	private fun collectCryptographicSuites(policy: EtsiValidationPolicy): List<CryptographicSuite> =
		listOfNotNull(
			runCatching { policy.getSignatureCryptographicConstraint(Context.SIGNATURE) }.getOrNull(),
			runCatching { policy.getSignatureCryptographicConstraint(Context.COUNTER_SIGNATURE) }.getOrNull(),
			runCatching {
				policy.getCertificateCryptographicConstraint(
					Context.SIGNATURE,
					SubContext.SIGNING_CERT
				)
			}.getOrNull(),
			runCatching { policy.getEvidenceRecordCryptographicConstraint() }.getOrNull()
		)
	
	/**
	 * Remove disabled hash and encryption algorithms from the JAXB `AcceptableDigestAlgo`
	 * and `AcceptableEncryptionAlgo` lists across the entire policy tree.
	 *
	 * DSS resolves accepted algorithms hierarchically (context-specific overrides the
	 * global `Cryptographic` section), so this method collects every reachable JAXB
	 * [CryptographicConstraint] and strips disabled entries from all of them.
	 */
	private fun removeDisabledAlgorithms(
		policy: ValidationPolicy,
		disabledHash: Set<HashAlgorithm>,
		disabledEncryption: Set<EncryptionAlgorithm>,
	) {
		if (policy !is EtsiValidationPolicy) return
		
		val disabledDigestNames = disabledHash.map { it.dssName }.toSet()
		val disabledEncryptionNames = disabledEncryption.map { it.dssName }.toSet()
		
		collectJaxbCryptographicConstraints(policy).forEach { constraint ->
			if (disabledDigestNames.isNotEmpty()) {
				constraint.acceptableDigestAlgo?.algos?.removeAll { it.value in disabledDigestNames }
			}
			if (disabledEncryptionNames.isNotEmpty()) {
				constraint.acceptableEncryptionAlgo?.algos?.removeAll { it.value in disabledEncryptionNames }
			}
		}
	}
	
	/**
	 * Collect every non-null JAXB [CryptographicConstraint] reachable from the policy.
	 *
	 * Covers:
	 * - Global `Cryptographic` section
	 * - Signature / CounterSignature → BasicSignatureConstraints → CryptographicConstraint
	 * - Signature / CounterSignature → BasicSignatureConstraints → SigningCertificate → CryptographicConstraint
	 * - Signature / CounterSignature → BasicSignatureConstraints → CACertificate → CryptographicConstraint
	 * - Timestamp → BasicSignatureConstraints (and its nested cert constraints)
	 * - Revocation → BasicSignatureConstraints (and its nested cert constraints)
	 * - EvidenceRecord → BasicSignatureConstraints (and its nested cert constraints)
	 */
	private fun collectJaxbCryptographicConstraints(policy: EtsiValidationPolicy): List<CryptographicConstraint> {
		val result = mutableListOf<CryptographicConstraint>()
		
		policy.cryptographic?.let { result += it }
		
		fun addFromBasicConstraints(basic: eu.europa.esig.dss.policy.jaxb.BasicSignatureConstraints?) {
			basic?.cryptographic?.let { result += it }
			basic?.signingCertificate?.cryptographic?.let { result += it }
			basic?.caCertificate?.cryptographic?.let { result += it }
		}
		
		addFromBasicConstraints(policy.signatureConstraints?.basicSignatureConstraints)
		addFromBasicConstraints(policy.counterSignatureConstraints?.basicSignatureConstraints)
		addFromBasicConstraints(policy.timestampConstraints?.basicSignatureConstraints)
		addFromBasicConstraints(policy.revocationConstraints?.basicSignatureConstraints)
		policy.evidenceRecordConstraints?.cryptographic?.let { result += it }
		
		return result
	}
	
	private companion object {
		/** The date format DSS reads an `AlgoExpirationDate` with when the policy names none. */
		const val DSS_DEFAULT_DATE_FORMAT = "yyyy-MM-dd"
	}
}