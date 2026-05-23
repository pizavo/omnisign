package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import kotlinx.serialization.Serializable

/**
 * Serializable mirror of [DocumentTimestampInfo] returned by `POST /api/v1/timestamp/inspect`.
 *
 * The two flags let a remote client decide which target PAdES levels are valid extensions for
 * the given document without running a full validation pass: when [hasDocumentTimestamp] is
 * `true` the document is already at B-LTA, so a `SIGNATURE_TIMESTAMP` (B-T) target would be a
 * downgrade; when [containsLtData] is `true` the document is at B-LT or higher, so a B-LT
 * extension is a no-op. Mirrors the desktop's pre-flight check that powers
 * `TimestampDialogState.Ready.disabledTypes`.
 *
 * @property hasDocumentTimestamp Whether the document contains a document-level timestamp
 *   (archive or document timestamp). Presence implies the document is at B-LTA level.
 * @property containsLtData Whether any signature in the document already includes LT-level
 *   data (revocation information), meaning the document is at B-LT or higher.
 */
@Serializable
data class DocumentTimestampInfoResponse(
	val hasDocumentTimestamp: Boolean,
	val containsLtData: Boolean,
)

/**
 * Map a [DocumentTimestampInfo] to its serializable [DocumentTimestampInfoResponse] mirror.
 */
fun DocumentTimestampInfo.toResponse() = DocumentTimestampInfoResponse(
	hasDocumentTimestamp = hasDocumentTimestamp,
	containsLtData = containsLtData,
)
