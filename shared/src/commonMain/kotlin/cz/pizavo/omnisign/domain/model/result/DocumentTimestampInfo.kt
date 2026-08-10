package cz.pizavo.omnisign.domain.model.result

import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import kotlinx.serialization.Serializable

/**
 * Lightweight summary of the timestamp and signature state of an existing PDF.
 *
 * Used by the timestamp dialog to determine which extension options are valid and how to
 * label them, without requiring a full validation run. Serialized directly as the
 * `POST /api/v1/timestamp/inspect` response body for the web target.
 *
 * @property hasDocumentTimestamp Whether the document contains a document-level timestamp
 *   (archive or document timestamp). A structural fact about the PDF, not a level: a document
 *   timestamp over validation data that is missing or unusable does not make the document B-LTA.
 *   Use it to decide which extension options apply, and [level] to say what the document *is*.
 * @property containsLtData Whether the document carries usable long-term validation material, so
 *   that it is at B-LT or higher. Derived from [level], never from the presence of a `/DSS`
 *   dictionary: that dictionary can hold certificates without any revocation data — or nothing at
 *   all — which is exactly how a B-T document ends up labelled B-LT by tools that read structure
 *   instead of content. A level that could not be established leaves this `false`.
 * @property hasSignatureTimestamp Whether any signature embeds a signature timestamp — the
 *   unsigned attribute that marks B-T. This is what tells a B-B document apart from a B-T one,
 *   a distinction the structural flags above cannot make on their own. It is typically also true
 *   once [containsLtData] is (B-LT builds on B-T), so it is only decisive when no LT data is
 *   present.
 * @property level The PAdES baseline level the document is at, as the validation report would
 *   state it, or `null` when it could not be established (an unparseable document, one with no
 *   signature, or a signature outside the four baseline levels). Callers that display a level
 *   should prefer it over inferring one from the flags above, which describe structure rather
 *   than conformance.
 */
@Serializable
data class DocumentTimestampInfo(
    val hasDocumentTimestamp: Boolean,
    val containsLtData: Boolean,
    val hasSignatureTimestamp: Boolean = false,
    val level: SignatureLevel? = null,
)

