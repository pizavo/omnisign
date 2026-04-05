package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning


/**
 * Translates raw DSS warning messages (from [CollectingStatusAlert] and [DssLogCapture])
 * into grouped, user-friendly summaries.
 *
 * DSS produces highly technical diagnostics containing certificate hashes, Base64 blobs,
 * and ASN.1 error details. This class classifies each raw message into a
 * [WarningCategory], groups them, and emits one human-readable sentence per category.
 * Messages that do not match any known pattern are kept verbatim, so no information is
 * silently lost.
 *
 * The [sanitize] entry point returns a [SanitizedWarnings] bundle containing both the
 * user-friendly summaries (for display) and the original raw list (for JSON / verbose
 * output).
 */
class DssWarningSanitizer {
	
	/**
	 * Classify and group [rawWarnings] into user-friendly summary lines.
	 *
	 * Each raw message is matched against known DSS warning patterns and placed into a
	 * [WarningCategory] bucket. Categories are emitted in enum declaration order, one
	 * summary line per bucket, followed by any unmatched messages verbatim.
	 *
	 * Categories listed in [suppressedCategories] are still classified (and appear in the
	 * returned [SanitizedWarnings.categories] set and [SanitizedWarnings.raw] list) but
	 * are **excluded** from [SanitizedWarnings.annotatedSummaries]. This allows callers
	 * to silence context-dependent noise (e.g. [WarningCategory.REVOCATION_NOT_FOUND]
	 * during signing, where the PAdES extension process embeds revocation data even when
	 * the verifier's pre-extension check fires a warning).
	 *
	 * @param rawWarnings Raw warning strings from [CollectingStatusAlert] and [DssLogCapture].
	 * @param certIdNames Optional mapping from DSS certificate/timestamp identifier to a
	 *   human-readable name (e.g. subject CN). When present, matching entries are propagated
	 *   to [AnnotatedWarning.idNames] so the UI can display friendly names alongside IDs.
	 * @param suppressedCategories Categories whose warnings are classified but not emitted
	 *   in the user-facing [SanitizedWarnings.annotatedSummaries].
	 */
	fun sanitize(
		rawWarnings: List<String>,
		certIdNames: Map<String, String> = emptyMap(),
		suppressedCategories: Set<WarningCategory> = emptySet(),
	): SanitizedWarnings {
		if (rawWarnings.isEmpty()) return SanitizedWarnings(emptyList(), emptyList())
		
		val buckets = mutableMapOf<WarningCategory, MutableSet<String>>()
		val unmatched = mutableListOf<String>()
		
		for (raw in rawWarnings) {
			val match = classify(raw)
			if (match != null) {
				buckets.getOrPut(match.first) { mutableSetOf() } += match.second
			} else {
				unmatched += raw
			}
		}
		
		val annotated = mutableListOf<AnnotatedWarning>()
		for (category in WarningCategory.entries) {
			val ids = buckets[category] ?: continue
			if (category in suppressedCategories) continue
			val filteredIds = ids.filter { it != PLACEHOLDER_ID }.sorted()
			val names = filteredIds
				.mapNotNull { id -> certIdNames[id]?.let { id to it } }
				.toMap()
			annotated += AnnotatedWarning(
				summary = category.toSummary(ids),
				affectedIds = filteredIds,
				idNames = names,
			)
		}
		for (raw in unmatched) {
			annotated += AnnotatedWarning(summary = raw)
		}
		
		return SanitizedWarnings(
			annotatedSummaries = annotated,
			raw = rawWarnings,
			categories = buckets.keys.toSet(),
		)
	}
	
	/**
	 * Try to match [message] against the known DSS warning patterns.
	 *
	 * @return A pair of the matched [WarningCategory] and an identifier extracted from
	 *   the message (e.g., a full DSS certificate hash like `C-AAAA…`), or null when
	 *   no pattern matches.
	 */
	internal fun classify(message: String): Pair<WarningCategory, String>? {
		for ((category, patterns) in PATTERNS) {
			for (pattern in patterns) {
				val match = pattern.find(message) ?: continue
				val id = match.groupValues.getOrNull(1)
					?.takeIf { it.isNotBlank() }
					?: PLACEHOLDER_ID
				return category to id
			}
		}
		return null
	}
	
	companion object {
		private const val PLACEHOLDER_ID = "_"
		
		private const val CERT_ID = """(C-[A-F0-9]+)"""
		private const val TS_ID = """(T-[A-F0-9]+)"""
		
		private val PATTERNS: Map<WarningCategory, List<Regex>> = mapOf(
			
			WarningCategory.REVOCATION_NOT_FOUND to listOf(
				Regex("""No revocation found for the certificate $CERT_ID"""),
				Regex("""No revocation data found.*?$CERT_ID"""),
				Regex("""OCSP DSS Exception.*?Unable to retrieve OCSP response.*?'$CERT_ID'"""),
				Regex("""Unable to retrieve OCSP response.*?'$CERT_ID'"""),
				Regex("""CRL DSS Exception.*?Unable to download CRL.*?'$CERT_ID'"""),
				Regex("""Unable to download CRL.*?'$CERT_ID'"""),
			),
			
			WarningCategory.REVOCATION_UNTRUSTED_CHAIN to listOf(
				Regex("""Revocation data is skipped for untrusted certificate.*?$CERT_ID"""),
				Regex("""External revocation check is skipped for untrusted certificate\s*:\s*$CERT_ID"""),
				Regex("""Revocation data is missing for one or more certificate.*?untrusted"""),
			),
			
			WarningCategory.REVOCATION_STATUS_UNKNOWN to listOf(
				Regex("""certificate\s+'$CERT_ID'\s+is not known to be not revoked"""),
				Regex("""certificate\s+'$CERT_ID'\s+does not contain a valid revocation data"""),
			),
			
			WarningCategory.REVOCATION_POE_MISSING to listOf(
				Regex("""Revocation data is missing for one or more POE"""),
			),
			
			WarningCategory.FRESH_REVOCATION_MISSING to listOf(
				Regex("""Fresh revocation data is missing"""),
			),
			
			WarningCategory.TIMESTAMP_UNTRUSTED to listOf(
				Regex("""POE extraction is skipped for untrusted timestamp\s*:\s*$TS_ID"""),
			),
			
			WarningCategory.CERTIFICATE_PARSE_ERROR to listOf(
				Regex("""Unable to load the alternative name"""),
				Regex("""Unable to parse the certificatePolicies extension"""),
				Regex("""Unable to retrieve the ASN1Sequence"""),
			),
			
			WarningCategory.TSP_FAILURE to listOf(
				Regex("""TSP Failure info.*?PKIFailureInfo"""),
				Regex("""No timestamp token has been retrieved"""),
			),
		)
	}
	
	/**
	 * Known categories of DSS warnings with user-facing summary templates.
	 */
	enum class WarningCategory {
		
		/**
		 * CRL/OCSP revocation data could not be downloaded for one or more certificates.
		 */
		REVOCATION_NOT_FOUND {
			override fun toSummary(ids: Set<String>) =
				"Revocation data (CRL/OCSP) could not be retrieved for " +
						"${pluralCerts(ids.size)}. " +
						"Long-term signature validity may be affected."
		},
		
		/**
		 * Revocation checks were skipped because the certificate chain is not anchored
		 * in a configured trusted list.
		 */
		REVOCATION_UNTRUSTED_CHAIN {
			override fun toSummary(ids: Set<String>) =
				"Revocation checks were skipped for ${pluralCerts(ids.size)} " +
						"in untrusted chain(s). This is expected when no trusted list is configured."
		},
		
		/**
		 * A certificate's revocation status could not be confirmed (neither revoked nor good).
		 */
		REVOCATION_STATUS_UNKNOWN {
			override fun toSummary(ids: Set<String>) =
				"Revocation status could not be confirmed for ${pluralCerts(ids.size)}. " +
						"The certificate chain may not be fully trusted by all validators."
		},
		
		/**
		 * Revocation data needed for proof-of-existence is missing.
		 */
		REVOCATION_POE_MISSING {
			override fun toSummary(ids: Set<String>) =
				"Revocation data required for proof-of-existence is missing " +
						"for ${pluralCerts(ids.size)}."
		},
		
		/**
		 * Cached revocation data exists, but a fresh response could not be obtained.
		 */
		FRESH_REVOCATION_MISSING {
			override fun toSummary(ids: Set<String>) =
				"Fresh revocation data could not be obtained for ${pluralCerts(ids.size)}. " +
						"Existing cached revocation data was used instead."
		},
		
		/**
		 * A timestamp's proof-of-existence could not be established because the TSA
		 * is not in the trusted list.
		 */
		TIMESTAMP_UNTRUSTED {
			override fun toSummary(ids: Set<String>) = buildString {
				append("Proof-of-existence could not be established for ")
				append(pluralTimestamps(ids.size))
				append(" because the issuing TSA is not in the trusted list.")
			}
		},
		
		/**
		 * One or more certificates contain malformed ASN.1 extensions that could not be
		 * parsed. Typically caused by non-standard third-party certificates (e.g., FreeTSA).
		 */
		CERTIFICATE_PARSE_ERROR {
			override fun toSummary(ids: Set<String>) =
				"Some certificates in the chain contain malformed extensions that could not " +
						"be fully parsed. This is typically caused by non-standard third-party " +
						"certificates (e.g. TSA) and does not affect the signature itself."
		},
		
		/**
		 * The timestamp server returned a failure or its response could not be processed.
		 * This is a non-fatal warning captured from the DSS log; if the TSP failure was
		 * critical, the operation would have failed with a dedicated timestamp error instead.
		 */
		TSP_FAILURE {
			override fun toSummary(ids: Set<String>) =
				"The timestamp server reported a problem (PKIFailureInfo). " +
						"If the operation succeeded, the timestamp may have been obtained on a retry."
		};
		
		/**
		 * Produce a single user-facing summary line for all [ids] that fell into this category.
		 */
		abstract fun toSummary(ids: Set<String>): String
		
		/**
		 * Whether this category indicates that revocation data could not be obtained.
		 *
		 * Used to trigger the revocation warning confirmation flow when signing
		 * at B-LT or B-LTA level.
		 */
		val isRevocationRelated: Boolean
			get() = this in REVOCATION_CATEGORIES
		
		protected fun pluralCerts(count: Int) =
			if (count == 1) "1 certificate" else "$count certificates"
		
		protected fun pluralTimestamps(count: Int) =
			if (count == 1) "1 timestamp" else "$count timestamps"
		
		companion object {
			private val REVOCATION_CATEGORIES = setOf(
				REVOCATION_STATUS_UNKNOWN,
				REVOCATION_POE_MISSING,
			)
		}
	}
}

/**
 * Result of [DssWarningSanitizer.sanitize].
 *
 * @property annotatedSummaries Grouped, user-friendly warnings with affected entity IDs preserved.
 * @property raw The original raw warning strings for JSON / verbose output.
 * @property categories The set of [DssWarningSanitizer.WarningCategory] buckets that had at least one match.
 */
data class SanitizedWarnings(
	val annotatedSummaries: List<AnnotatedWarning>,
	val raw: List<String>,
	val categories: Set<DssWarningSanitizer.WarningCategory> = emptySet(),
) {
	/**
	 * Plain-text summaries derived from [annotatedSummaries] for backward-compatible consumers.
	 */
	val summaries: List<String>
		get() = annotatedSummaries.map { it.summary }
	
	/**
	 * Whether any matched category relates to missing or failed revocation data.
	 */
	val hasRevocationWarnings: Boolean
		get() = categories.any { it.isRevocationRelated }
}

