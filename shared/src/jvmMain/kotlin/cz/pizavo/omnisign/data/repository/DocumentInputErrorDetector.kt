package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.pades.exception.InvalidPasswordException
import eu.europa.esig.dss.pades.exception.ProtectedDocumentException

/**
 * Classifies input-document failures that arise during a PAdES operation (signing or extension):
 * an encrypted / password-protected PDF, a PDF locked against further signatures, or input that is
 * not a PDF at all.
 *
 * [isEncrypted] and [isLocked] walk the full cause chain and key off exception types, DSS's own and
 * the [SignatureRestrictionException] that [SignatureRestrictionChecker] raises, so they are
 * independent of message wording and DSS version. [looksLikePdf] is a cheap structural
 * pre-check on the raw bytes, run before the document is handed to DSS so a non-PDF is reported with
 * a clear, actionable error instead of DSS's generic input exception.
 *
 * DSS throws a single, overloaded `IllegalInputException` for many unrelated conditions ("not a
 * PDF", "no signature to extend", a malformed byte range, …), so classifying that type as
 * "malformed" is deliberately avoided: it cannot tell a corrupt file apart from a valid one the
 * operation simply cannot be applied to. Such residual failures surface as the operation's generic
 * error instead.
 */
class DocumentInputErrorDetector {

	/**
	 * Whether [exception] — or any exception in its cause chain — signals an encrypted or
	 * password-protected PDF that cannot be modified (DSS `ProtectedDocumentException` /
	 * `InvalidPasswordException`).
	 *
	 * A [SignatureRestrictionException] does not count, though it is a `ProtectedDocumentException`:
	 * it is about a document locked against further signatures, which [isLocked] reports.
	 */
	fun isEncrypted(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }
			.any {
				(it is ProtectedDocumentException && it !is SignatureRestrictionException) ||
						it is InvalidPasswordException
			}

	/**
	 * Whether [exception] — or any exception in its cause chain — is the [SignatureRestrictionException]
	 * raised when the document's certification or an earlier signature's lock forbids a new signature.
	 */
	fun isLocked(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }.any { it is SignatureRestrictionException }

	/**
	 * Whether [bytes] carry a PDF header (`%PDF-`) within the first [PDF_HEADER_SEARCH_LIMIT] bytes —
	 * the lenient window the PDF specification permits before the header.
	 *
	 * This is a structural plausibility check, not a full parse: it confirms the input is a PDF, not
	 * that its body is well-formed. A header-bearing but otherwise broken document is left for DSS to
	 * reject (and surfaces as the operation's generic failure).
	 */
	fun looksLikePdf(bytes: ByteArray): Boolean {
		val header = "%PDF-".toByteArray(Charsets.US_ASCII)
		if (bytes.size < header.size) return false
		val lastStart = minOf(bytes.size - header.size, PDF_HEADER_SEARCH_LIMIT)
		return (0..lastStart).any { start -> header.indices.all { bytes[start + it] == header[it] } }
	}

	companion object {
		/** The PDF specification allows the `%PDF-` header anywhere within the first 1 KB of the file. */
		private const val PDF_HEADER_SEARCH_LIMIT = 1024
	}
}
