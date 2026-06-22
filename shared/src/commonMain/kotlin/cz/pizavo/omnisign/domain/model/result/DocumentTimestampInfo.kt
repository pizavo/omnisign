package cz.pizavo.omnisign.domain.model.result

import kotlinx.serialization.Serializable

/**
 * Lightweight summary of the timestamp and signature state of an existing PDF.
 *
 * Used by the timestamp dialog to determine which extension options are valid and how to
 * label them, without requiring a full validation run. Serialized directly as the
 * `POST /api/v1/timestamp/inspect` response body for the web target.
 *
 * @property hasDocumentTimestamp Whether the document contains a document-level timestamp
 *   (archive or document timestamp). Presence implies the document is at B-LTA level.
 * @property containsLtData Whether any signature in the document already includes
 *   LT-level data (revocation information), meaning the document is at B-LT or higher.
 * @property hasSignatureTimestamp Whether any signature embeds a signature timestamp — the
 *   unsigned attribute that marks B-T. This is what tells a B-B document apart from a B-T one,
 *   a distinction the structural flags above cannot make on their own. It is typically also true
 *   once [containsLtData] is (B-LT builds on B-T), so it is only decisive when no LT data is
 *   present.
 */
@Serializable
data class DocumentTimestampInfo(
    val hasDocumentTimestamp: Boolean,
    val containsLtData: Boolean,
    val hasSignatureTimestamp: Boolean = false,
)

