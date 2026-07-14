package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import kotlin.time.Instant


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
	 * are **excluded** from [SanitizedWarnings.annotatedSummaries] and from
	 * [SanitizedWarnings.hasRevocationWarnings]. This allows callers to silence
	 * context-dependent noise (e.g. [WarningCategory.REVOCATION_NOT_FOUND] during signing,
	 * where the PAdES extension process embeds revocation data even when the verifier's
	 * pre-extension check fires a warning).
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
		val dueDates = mutableMapOf<WarningCategory, Instant>()
		val unmatched = mutableListOf<String>()

		for (raw in rawWarnings) {
			val match = classify(raw)
			if (match != null) {
				buckets.getOrPut(match.first) { mutableSetOf() } += match.second
				val due = extractNextUpdate(raw)
				val current = dueDates[match.first]
				if (due != null && (current == null || due > current)) dueDates[match.first] = due
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
				summary = category.toSummary(ids, dueDates[category]),
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
			suppressed = suppressedCategories,
		)
	}
	
	/**
	 * Try to match [message] against the known DSS warning patterns.
	 *
	 * Identifiers are collected independently of the matching pattern: a compound
	 * [CollectingStatusAlert] message names its objects *before* the detail text that
	 * describes them, so a pattern anchored on the detail cannot capture them, and a
	 * single message can name several objects at once.
	 *
	 * @return A pair of the matched [WarningCategory] and every DSS object identifier named
	 *   in the message (full certificate or timestamp hashes like `C-AAAA…`), or null when no
	 *   pattern matches. A message naming no identifier yields a single placeholder entry, so
	 *   every matched category reports at least one affected object.
	 */
	internal fun classify(message: String): Pair<WarningCategory, Set<String>>? {
		for ((category, patterns) in PATTERNS) {
			if (patterns.none { it.containsMatchIn(message) }) continue
			val ids = OBJECT_ID.findAll(message).map { it.value }.toSet()
			return category to ids.ifEmpty { setOf(PLACEHOLDER_ID) }
		}
		return null
	}

	/**
	 * The time by which newer revocation data is due for the certificates named in [message], as
	 * DSS reports it when it rejects revocation data for being older than the time it has to cover.
	 *
	 * The value is the `nextUpdate` of the rejected responses, so it is the issuer's own promise
	 * that newer data will exist *at or before* that time — augmenting earlier may well work, but
	 * augmenting after it is guaranteed to. When a message names several certificates, the latest
	 * of their times is taken: an earlier one would leave the certificates whose issuer has not
	 * refreshed yet still uncovered.
	 *
	 * @return The due time, or null when DSS reported none (no response carried a `nextUpdate`).
	 */
	private fun extractNextUpdate(message: String): Instant? =
		NEXT_UPDATE.findAll(message)
			.mapNotNull { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() }
			.maxOrNull()
	
	companion object {
		private const val PLACEHOLDER_ID = "_"
		
		private const val CERT_ID = """C-[A-F0-9]+"""
		private const val TS_ID = """T-[A-F0-9]+"""
		
		/**
		 * Matches every DSS object identifier — certificate or timestamp — named in a message.
		 *
		 * [CollectingStatusAlert] renders a compound status as
		 * `<base message> [<id>: <detail>; <id>: <detail>]`, so one raw warning can name several
		 * objects, each ahead of the detail that describes it.
		 */
		private val OBJECT_ID = Regex("""\b[CT]-[A-F0-9]{4,}\b""")

		private const val ISO_INSTANT = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z"""

		/**
		 * Captures the time by which newer revocation data is due, as DSS renders it with
		 * `DSSUtils.formatDateToRFC` — RFC 3339, always in UTC.
		 *
		 * Both of DSS's wordings are accepted: the per-certificate detail of a status
		 * (`The nextUpdate available after : [<time>]`) and the aggregated status message
		 * (`NextUpdate time : <time>`).
		 */
		private val NEXT_UPDATE = Regex(
			"""nextUpdate (?:available after|time)\s*:\s*\[?($ISO_INSTANT)]?""",
			RegexOption.IGNORE_CASE,
		)
		
		/**
		 * Warning patterns per category, tried in iteration order; the first category with a
		 * matching pattern wins.
		 *
		 * The order is load-bearing. DSS raises three distinct statuses — missing revocation
		 * data, an uncovered proof-of-existence, and revocation data that predates the best
		 * signature time — and attaches the same per-certificate detail text (`No revocation
		 * data found…`) to all of them; only the base message tells them apart. Categories keyed
		 * on a base message therefore have to be tried before those keyed on a detail text, or a
		 * proof-of-existence warning would be reported as a failed CRL/OCSP download.
		 */
		private val PATTERNS: Map<WarningCategory, List<Regex>> = mapOf(
		
			WarningCategory.REVOCATION_POE_MISSING to listOf(
				Regex("""Revocation data is missing for one or more POE.*?No revocation data found for certificate"""),
			),
			
			WarningCategory.REVOCATION_POE_STALE to listOf(
				Regex("""Revocation data is missing for one or more POE"""),
			),
			
			WarningCategory.FRESH_REVOCATION_MISSING to listOf(
				Regex("""Fresh revocation data is missing"""),
			),
			
			WarningCategory.REVOCATION_UNTRUSTED_CHAIN to listOf(
				Regex("""Revocation data is skipped for untrusted certificate"""),
				Regex("""External revocation check is skipped for untrusted certificate\s*:\s*$CERT_ID"""),
				Regex("""Revocation data is missing for one or more certificate.*?untrusted"""),
			),
			
			WarningCategory.REVOCATION_NOT_FOUND to listOf(
				Regex("""No revocation found for the certificate $CERT_ID"""),
				Regex("""No revocation data found"""),
				Regex("""Revocation data is missing for one or more certificate"""),
				Regex("""Unable to retrieve OCSP response.*?'$CERT_ID'"""),
				Regex("""Unable to download CRL.*?'$CERT_ID'"""),
			),
			
			WarningCategory.REVOCATION_STATUS_UNKNOWN to listOf(
				Regex("""certificate\s+'$CERT_ID'\s+is not known to be not revoked"""),
				Regex("""certificate\s+'$CERT_ID'\s+does not contain a valid revocation data"""),
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
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) =
				"Revocation data (CRL/OCSP) could not be retrieved for " +
						"${pluralCerts(ids.size)}. " +
						"Long-term signature validity may be affected."
		},
		
		/**
		 * Revocation checks were skipped because the certificate chain is not anchored
		 * in a configured trusted list.
		 */
		REVOCATION_UNTRUSTED_CHAIN {
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) =
				"Revocation checks were skipped for ${pluralCerts(ids.size)} " +
						"in untrusted chain(s). This is expected when no trusted list is configured."
		},
		
		/**
		 * A certificate's revocation status could not be confirmed (neither revoked nor good).
		 */
		REVOCATION_STATUS_UNKNOWN {
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) =
				"Revocation status could not be confirmed for ${pluralCerts(ids.size)}. " +
						"The certificate chain may not be fully trusted by all validators."
		},
		
		/**
		 * No revocation data at all could be collected for a certificate whose
		 * proof-of-existence a timestamp has to cover.
		 */
		REVOCATION_POE_MISSING {
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) =
				"Revocation data required for proof-of-existence is missing " +
						"for ${pluralCerts(ids.size)}."
		},
		
		/**
		 * Revocation data was collected, but every response predates the timestamp whose
		 * proof-of-existence it has to cover, so DSS does not count it.
		 *
		 * The gap is genuine — a response issued before the timestamp cannot say whether the
		 * certificate was revoked at the moment the timestamp vouches for — so the warning is
		 * always reported. It is deliberately **not** revocation-related, and therefore does not
		 * raise the confirmation prompt: the data was obtained and embedded, and signing again
		 * reproduces the condition exactly, so there is nothing for the signer to decide. Only
		 * augmenting the signature once newer revocation data is published closes the gap.
		 *
		 * Expected when signing straight to B-LT, because the timestamp is created moments before
		 * the revocation data is fetched: only a responder that signs a fresh response per request
		 * can produce a `thisUpdate` later than the timestamp, and a cached response never can.
		 */
		REVOCATION_POE_STALE {
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) = buildString {
				append("Revocation data for ${pluralCerts(ids.size)} predates the signature timestamp, ")
				append("so it does not cover the timestamp's proof-of-existence. ")
				if (nextUpdate != null) {
					append("Newer revocation data is due by $nextUpdate — augmenting the signature ")
					append("after that time closes the gap.")
				} else {
					append("Augmenting the signature once newer revocation data is published closes the gap.")
				}
			}
		},
		
		/**
		 * Cached revocation data exists, but a fresh response could not be obtained.
		 */
		FRESH_REVOCATION_MISSING {
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) =
				"Fresh revocation data could not be obtained for ${pluralCerts(ids.size)}. " +
						"Existing cached revocation data was used instead."
		},
		
		/**
		 * A timestamp's proof-of-existence could not be established because the TSA
		 * is not in the trusted list.
		 */
		TIMESTAMP_UNTRUSTED {
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) = buildString {
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
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) =
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
			override fun toSummary(ids: Set<String>, nextUpdate: Instant?) =
				"The timestamp server reported a problem (PKIFailureInfo). " +
						"If the operation succeeded, the timestamp may have been obtained on a retry."
		};
		
		/**
		 * Produce a single user-facing summary line for all [ids] that fell into this category.
		 *
		 * @param nextUpdate The time by which the issuers promise newer revocation data, when DSS
		 *   reported one. Only categories raised by revocation data being older than the time it
		 *   has to cover act on it; every other category ignores it.
		 */
		abstract fun toSummary(ids: Set<String>, nextUpdate: Instant? = null): String
		
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
 * @property categories The set of [DssWarningSanitizer.WarningCategory] buckets that had at least one
 *   match, including the buckets the caller asked to suppress.
 * @property suppressed The categories the caller asked to hide, as passed to [DssWarningSanitizer.sanitize].
 */
data class SanitizedWarnings(
	val annotatedSummaries: List<AnnotatedWarning>,
	val raw: List<String>,
	val categories: Set<DssWarningSanitizer.WarningCategory> = emptySet(),
	val suppressed: Set<DssWarningSanitizer.WarningCategory> = emptySet(),
) {
	/**
	 * Plain-text summaries derived from [annotatedSummaries] for backward-compatible consumers.
	 */
	val summaries: List<String>
		get() = annotatedSummaries.map { it.summary }
	
	/**
	 * Whether any category reported to the user relates to missing or failed revocation data.
	 *
	 * [suppressed] categories are excluded: a warning the caller deliberately hides must not
	 * trigger the revocation confirmation prompt either, or the prompt would appear with nothing
	 * in the warning list to justify it.
	 */
	val hasRevocationWarnings: Boolean
		get() = categories.any { it.isRevocationRelated && it !in suppressed }
}

