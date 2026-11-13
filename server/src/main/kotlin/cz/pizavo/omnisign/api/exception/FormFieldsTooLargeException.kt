package cz.pizavo.omnisign.api.exception

/**
 * Thrown when the form fields of a multipart request, together with the headers of all its parts,
 * take more than [maxBytes] bytes.
 *
 * The server keeps the form fields in memory until the request completes, and reads each part's
 * headers into memory to find the part's name, so this bounds how much of one request it holds in
 * memory. The file part does not count: it is streamed to disk and bounded by `maxFileSize`.
 *
 * Caught by the StatusPages plugin and mapped to HTTP 413 Payload Too Large.
 *
 * @property maxBytes The most bytes the form fields and part headers of one request may take.
 */
class FormFieldsTooLargeException(val maxBytes: Long) : RuntimeException(
	"The form fields and part headers of the multipart request exceed $maxBytes bytes.",
)
