package cz.pizavo.omnisign.api.exception

/**
 * Thrown when an uploaded file exceeds the server's configured maximum file size.
 *
 * Caught by the StatusPages plugin and mapped to HTTP 413 Payload Too Large.
 *
 * @param actualSize Size of the uploaded file in bytes.
 * @param maxSize Configured maximum size in bytes.
 */
class FileTooLargeException(
	val actualSize: Long,
	val maxSize: Long,
) : RuntimeException(
	"Uploaded file size ($actualSize bytes) exceeds the maximum allowed size ($maxSize bytes)"
)

