package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.value.formatDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One revocation check DSS found or performed for the signing certificate.
 *
 * Each entry mirrors a single DSS `CertificateRevocationWrapper` faithfully — `OCSP` or `CRL`,
 * where it came from, the responder's own times, and the status it asserted. A signature can carry
 * several (e.g. an embedded token sealed at signing time plus a fresh one DSS fetched online during
 * validation); all of them are surfaced rather than one being chosen, so the panel never hides a
 * check that happened or masks a fresh result with a stale embedded one.
 *
 * @property method Revocation mechanism — `"OCSP"` or `"CRL"`.
 * @property status Status the responder asserted — `"GOOD"`, `"REVOKED"`, or `"UNKNOWN"`.
 * @property revoked Convenience flag: true when [status] is `"REVOKED"`.
 * @property embedded Whether the token came from inside the document (DSS/VRI dictionary, CMS, …)
 *   as opposed to a remote fetch or local cache. For PAdES, "inside the document" is the `/DSS`
 *   Document Security Store dictionary (and the per-signature `/VRI` entries) — long-term validation
 *   material carried in the file itself, per ISO 32000-2 §12.8.4.3 and ETSI EN 319 142-1.
 * @property sealedByTimestamp Whether a non-failed document/archive timestamp covers this token,
 *   giving it a proof-of-existence (the durable, self-contained proof of status at signing).
 * @property origin Raw DSS revocation origin (e.g. `"DSS_DICTIONARY"`, `"EXTERNAL"`) for auditing.
 * @property sourceUrl Address the token was obtained from (OCSP responder / CRL distribution point)
 *   when DSS recorded one; `null` for tokens read from the document, where nothing was contacted.
 * @property producedAt The responder's own production time — OCSP `producedAt` / CRL signing time.
 * @property thisUpdate Start of the validity window the responder vouches for.
 * @property nextUpdate End of the validity window, after which fresher revocation data is expected.
 * @property revocationDate When the certificate was revoked; only meaningful when [revoked].
 * @property reason Revocation reason code (e.g. `"KEY_COMPROMISE"`); only meaningful when [revoked].
 */
@Serializable
data class RevocationInfo(
    val method: String,
    val status: String,
    val revoked: Boolean,
    val embedded: Boolean,
    val sealedByTimestamp: Boolean,
    val origin: String,
    val sourceUrl: String? = null,
    val producedAt: Instant? = null,
    val thisUpdate: Instant? = null,
    val nextUpdate: Instant? = null,
    val revocationDate: Instant? = null,
    val reason: String? = null,
)

/**
 * The token that best represents the certificate's status *at signing time*: a timestamp-sealed
 * token first (it carries a proof-of-existence at signing), then any embedded token, then the
 * earliest-produced. Used only to phrase the per-signature [revocationConclusion]; the full list is
 * always rendered, so this picks a spokesperson without hiding any of the others.
 */
fun List<RevocationInfo>.signingTimeRepresentative(): RevocationInfo? =
    sortedWith(
        compareByDescending<RevocationInfo> { it.sealedByTimestamp }
            .thenByDescending { it.embedded }
            .thenBy { it.producedAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }
    ).firstOrNull()

/**
 * One-line conclusion about the signing certificate's revocation status as of [asOf]
 * (best-signature-time), derived from the [signingTimeRepresentative]. Returns `null` when there is
 * no revocation data. The supporting details (method, source, responder, times) are presented as
 * structured fields per token, so this stays a bare statement of the outcome.
 */
fun List<RevocationInfo>.revocationConclusion(asOf: Instant): String? {
    val representative = signingTimeRepresentative() ?: return null
    val verb = when {
        representative.revoked -> "was revoked"
        representative.status == "GOOD" -> "was not revoked"
        else -> "had an undetermined revocation status"
    }
    return "The signing certificate $verb as of ${asOf.formatDateTime()}."
}

/**
 * Ordered label/value rows describing a single token for human-readable rendering. The signature
 * panel and the plain-text report share this, so the two never drift. Times are method-aware (OCSP
 * separates "Response produced" from "Status as of"/"Fresh until"; CRL uses "CRL issued"/"Next CRL
 * by"), and redundant or absent values are omitted.
 */
fun RevocationInfo.displayRows(): List<Pair<String, String>> = buildList {
    add("Status" to status)
    add("Method" to method)
    add("Source" to sourceLabel())
    sourceUrl?.let { add("Responder" to it) }
    addAll(timeRows())
    if (revoked) {
        revocationDate?.let { add("Revoked on" to it.formatDateTime()) }
        reason?.let { add("Reason" to it) }
    }
}

/**
 * Human-readable description of where the token came from and whether a document timestamp seals it.
 */
private fun RevocationInfo.sourceLabel(): String = when {
    embedded && sealedByTimestamp -> "Embedded in document, sealed by document timestamp"
    embedded -> "Embedded in document (not timestamp-protected)"
    else -> "Retrieved online during validation"
}

/**
 * Method-aware time rows. OCSP and CRL carry different time semantics, and a value equal to an
 * already-shown one (OCSP `thisUpdate` == `producedAt`) or absent is dropped.
 */
private fun RevocationInfo.timeRows(): List<Pair<String, String>> = when {
    method.equals("OCSP", ignoreCase = true) -> buildList {
        producedAt?.let { add("Response produced" to it.formatDateTime()) }
        thisUpdate?.takeIf { it != producedAt }?.let { add("Status as of" to it.formatDateTime()) }
        nextUpdate?.let { add("Fresh until" to it.formatDateTime()) }
    }
    method.equals("CRL", ignoreCase = true) -> buildList {
        thisUpdate?.let { add("CRL issued" to it.formatDateTime()) }
        nextUpdate?.let { add("Next CRL by" to it.formatDateTime()) }
    }
    else -> buildList {
        producedAt?.let { add("Produced at" to it.formatDateTime()) }
        thisUpdate?.takeIf { it != producedAt }?.let { add("This update" to it.formatDateTime()) }
        nextUpdate?.let { add("Next update" to it.formatDateTime()) }
    }
}
