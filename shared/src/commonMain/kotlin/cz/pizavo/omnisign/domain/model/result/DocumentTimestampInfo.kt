package cz.pizavo.omnisign.domain.model.result

/**
 * Lightweight summary of the timestamp and signature state of an existing PDF.
 *
 * Used by the timestamp dialog to determine which extension options are valid
 * without requiring a full validation run.
 *
 * @property hasDocumentTimestamp Whether the document contains a document-level timestamp
 *   (archive or document timestamp). Presence implies the document is at B-LTA level.
 * @property containsLtData Whether any signature in the document already includes
 *   LT-level data (revocation information), meaning the document is at B-LT or higher.
 */
data class DocumentTimestampInfo(
    val hasDocumentTimestamp: Boolean,
    val containsLtData: Boolean,
)

