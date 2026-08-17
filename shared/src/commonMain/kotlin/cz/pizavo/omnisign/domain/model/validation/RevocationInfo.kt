package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.value.DateFormat
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
 *
 * @param dateFormat Date style for [asOf]. A caller rendering into a surface that lets the user pick
 *   one — the desktop signature panel — must pass it, or this line disagrees with every other date
 *   beside it; see [displayRows].
 */
fun List<RevocationInfo>.revocationConclusion(
    asOf: Instant,
    dateFormat: DateFormat = DateFormat.SYSTEM,
): LocalizableText? {
    val representative = signingTimeRepresentative() ?: return null
    val key = when {
        representative.revoked -> MessageKey.REVOCATION_CONCLUSION_REVOKED
        representative.status == "GOOD" -> MessageKey.REVOCATION_CONCLUSION_NOT_REVOKED
        else -> MessageKey.REVOCATION_CONCLUSION_UNDETERMINED
    }
    return LocalizableText.of(key, asOf.formatDateTime(dateFormat = dateFormat))
}

/**
 * Ordered label/value rows describing a single token for human-readable rendering. The signature
 * panel and the plain-text report share this, so the two never drift. Times are method-aware (OCSP
 * separates "Response produced" from "Status as of"/"Fresh until"; CRL uses "CRL issued"/"Next CRL
 * by"), and redundant or absent values are omitted.
 *
 * Times are rendered here rather than handed back as instants, so [dateFormat] is how a caller says
 * which style to use. It defaults to [DateFormat.SYSTEM] for the plain-text report, which has no
 * user to ask; a surface that does let the user choose one — the desktop signature panel, through
 * `LocalAppDateFormat` — must pass that choice, or these rows are the only dates in the panel
 * ignoring it.
 *
 * @param dateFormat Date style for every time row.
 */
fun RevocationInfo.displayRows(
    dateFormat: DateFormat = DateFormat.SYSTEM,
): List<Pair<LocalizableText, LocalizableText>> = buildList {
    add(LocalizableText.of(MessageKey.REVOCATION_LABEL_STATUS) to statusText())
    add(LocalizableText.of(MessageKey.REVOCATION_LABEL_METHOD) to LocalizableText.Literal(method))
    add(LocalizableText.of(MessageKey.REVOCATION_LABEL_SOURCE) to sourceLabel())
    sourceUrl?.let { add(LocalizableText.of(MessageKey.REVOCATION_LABEL_RESPONDER) to LocalizableText.Literal(it)) }
    addAll(timeRows(dateFormat))
    if (revoked) {
        revocationDate?.let {
            add(
                LocalizableText.of(MessageKey.REVOCATION_LABEL_REVOKED_ON) to
                    LocalizableText.Literal(it.formatDateTime(dateFormat = dateFormat)),
            )
        }
        reason?.let { add(LocalizableText.of(MessageKey.REVOCATION_LABEL_REASON) to LocalizableText.Literal(it)) }
    }
}

/**
 * The responder-asserted status as a localizable value: the known `GOOD`/`REVOKED`/`UNKNOWN` states
 * are keyed; any other raw status passes through verbatim as a [LocalizableText.Literal].
 */
private fun RevocationInfo.statusText(): LocalizableText = when (status.uppercase()) {
    "GOOD" -> LocalizableText.of(MessageKey.REVOCATION_STATUS_GOOD)
    "REVOKED" -> LocalizableText.of(MessageKey.REVOCATION_STATUS_REVOKED)
    "UNKNOWN" -> LocalizableText.of(MessageKey.REVOCATION_STATUS_UNKNOWN)
    else -> LocalizableText.Literal(status)
}

/**
 * Human-readable description of where the token came from and whether a document timestamp seals it.
 */
private fun RevocationInfo.sourceLabel(): LocalizableText = when {
    embedded && sealedByTimestamp -> LocalizableText.of(MessageKey.REVOCATION_SOURCE_EMBEDDED_SEALED)
    embedded -> LocalizableText.of(MessageKey.REVOCATION_SOURCE_EMBEDDED)
    else -> LocalizableText.of(MessageKey.REVOCATION_SOURCE_ONLINE)
}

/**
 * Method-aware time rows. OCSP and CRL carry different time semantics, and a value equal to an
 * already-shown one (OCSP `thisUpdate` == `producedAt`) or absent is dropped.
 *
 * @param dateFormat Date style for every row, passed down from [displayRows].
 */
private fun RevocationInfo.timeRows(
    dateFormat: DateFormat,
): List<Pair<LocalizableText, LocalizableText>> {
    fun row(key: MessageKey, instant: Instant) =
        LocalizableText.of(key) to LocalizableText.Literal(instant.formatDateTime(dateFormat = dateFormat))

    return when {
        method.equals("OCSP", ignoreCase = true) -> buildList {
            producedAt?.let { add(row(MessageKey.REVOCATION_LABEL_RESPONSE_PRODUCED, it)) }
            thisUpdate?.takeIf { it != producedAt }?.let { add(row(MessageKey.REVOCATION_LABEL_STATUS_AS_OF, it)) }
            nextUpdate?.let { add(row(MessageKey.REVOCATION_LABEL_FRESH_UNTIL, it)) }
        }
        method.equals("CRL", ignoreCase = true) -> buildList {
            thisUpdate?.let { add(row(MessageKey.REVOCATION_LABEL_CRL_ISSUED, it)) }
            nextUpdate?.let { add(row(MessageKey.REVOCATION_LABEL_NEXT_CRL_BY, it)) }
        }
        else -> buildList {
            producedAt?.let { add(row(MessageKey.REVOCATION_LABEL_PRODUCED_AT, it)) }
            thisUpdate?.takeIf { it != producedAt }?.let { add(row(MessageKey.REVOCATION_LABEL_THIS_UPDATE, it)) }
            nextUpdate?.let { add(row(MessageKey.REVOCATION_LABEL_NEXT_UPDATE, it)) }
        }
    }
}
