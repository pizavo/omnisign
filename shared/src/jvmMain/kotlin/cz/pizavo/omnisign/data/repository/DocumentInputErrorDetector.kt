package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.pades.exception.InvalidPasswordException
import eu.europa.esig.dss.pades.exception.ProtectedDocumentException
import eu.europa.esig.dss.spi.exception.IllegalInputException

/**
 * Detects input-document failures thrown by the EU DSS library while extending a signature: an
 * encrypted / password-protected PDF, or a malformed (unparseable) one.
 *
 * Both checks are typed and walk the full cause chain, so they are independent of DSS's message
 * wording and version. They let [DssArchivingRepository] report a clear, actionable error instead
 * of a generic extension failure when the problem is the input file itself.
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
	 * Whether [exception] — or any exception in its cause chain — signals a malformed or unparseable
	 * input document (DSS `IllegalInputException`).
	 */
	fun isMalformed(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }
			.any { it is IllegalInputException }
}
