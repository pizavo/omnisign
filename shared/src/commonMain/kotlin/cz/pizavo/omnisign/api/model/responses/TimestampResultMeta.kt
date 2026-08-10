package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside a timestamped/extended PDF binary.
 *
 * Carried in the `X-OmniSign-Result` header of the `POST /api/v1/timestamp` response; the
 * extended PDF itself is the response body. Lives in `shared` so both the server (which
 * encodes it) and the web client (which decodes it) reference one definition.
 *
 * @property newLevel The PAdES level after timestamping/extension.
 * @property annotatedWarnings Warnings enriched with the DSS identifiers of the affected
 *   certificates or timestamps. Each [AnnotatedWarning.summary] is a localizable text a client
 *   renders in the active locale (falling back to English), and the per-warning identifier set
 *   backs tooltips or "show affected entity" affordances.
 * @property revocationDataMissing Whether the extension could not embed the revocation data
 *   [newLevel] needs, so the returned document did not reach it. Mirrors
 *   [cz.pizavo.omnisign.domain.model.result.ArchivingResult.revocationDataMissing] so a client can
 *   warn — or refuse to store the result — without parsing warning strings.
 */
@Serializable
data class TimestampResultMeta(
	val newLevel: String,
	val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
	val revocationDataMissing: Boolean = false,
)
