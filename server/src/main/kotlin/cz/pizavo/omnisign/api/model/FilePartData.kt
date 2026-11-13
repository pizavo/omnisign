package cz.pizavo.omnisign.api.model

import java.io.File

/**
 * A multipart file part whose content has been streamed to a temporary file on disk.
 *
 * Created by [cz.pizavo.omnisign.api.MultipartFormReader]. The upload is written directly to
 * [file] chunk-by-chunk as it arrives — the full content is never held in heap, so peak
 * memory per request is one I/O buffer rather than the whole file. [file] is a path
 * handle (a [File] is a lightweight wrapper around a filesystem path, not the bytes), so
 * holding it costs nothing memory-wise; it is what the caller deletes when the request
 * completes.
 *
 * @property name Form field name.
 * @property file Temporary file holding the streamed upload. The caller owns its
 *   lifecycle and must delete it once the request is done (see [cz.pizavo.omnisign.api.deleteFileParts]).
 * @property originalFileName File name taken from the multipart `Content-Disposition`
 *   `filename` attribute, or `null` when the client did not send one. Useful to surface
 *   the client-side name in domain artifacts (e.g., `documentName` on a validation
 *   report) instead of the generated temp file name.
 */
class FilePartData(
	val name: String?,
	val file: File,
	val originalFileName: String? = null,
)
