package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.pades.exception.InvalidPasswordException
import eu.europa.esig.dss.pades.exception.ProtectedDocumentException

/**
 * Classifies input-document failures that arise during a PAdES operation (signing or extension):
 * an encrypted / password-protected PDF, or input that is not a PDF at all.
 *
 * [isEncrypted] walks the full cause chain and keys off DSS's dedicated encryption exception types,
 * so it is independent of message wording and DSS version. [looksLikePdf] is a cheap structural
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
	 */
	fun isEncrypted(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }
			.any { it is ProtectedDocumentException || it is InvalidPasswordException }

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
