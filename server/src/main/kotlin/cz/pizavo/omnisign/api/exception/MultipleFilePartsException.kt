package cz.pizavo.omnisign.api.exception

/**
 * Thrown when a multipart request contains more than one file part.
 *
 * OmniSign's signing / validation / timestamping endpoints each operate on exactly one
 * document. Accepting multiple file parts would (a) be semantically ambiguous (which one
 * gets signed?) and (b) be a disk-amplification vector now that
 * [cz.pizavo.omnisign.api.collectParts] streams every file part to its own temp file —
 * a single request with N file parts would materialise N up-to-`maxFileSize` files on
 * disk. Rejecting the second file part keeps the contract explicit and bounds the
 * per-request disk footprint to one file.
 *
 * Caught by the StatusPages plugin and mapped to HTTP 400 Bad Request.
 */
class MultipleFilePartsException : RuntimeException(
	"Multipart request contains more than one file part; exactly one file is expected.",
)
