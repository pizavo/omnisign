package cz.pizavo.omnisign.api.exception

/**
 * Thrown when a multipart request carries more than [maxParts] parts.
 *
 * Every part besides the file is a form field, which [cz.pizavo.omnisign.api.MultipartFormReader]
 * keeps in memory until the request completes. The reader bounds the fields' total size but not
 * their number, and a body of tiny parts would otherwise become millions of objects on the heap.
 *
 * Caught by the StatusPages plugin and mapped to HTTP 400 Bad Request.
 *
 * @property maxParts The most parts a request may carry, the file included.
 */
class TooManyPartsException(val maxParts: Int) : RuntimeException(
	"Multipart request contains more than $maxParts parts.",
)
