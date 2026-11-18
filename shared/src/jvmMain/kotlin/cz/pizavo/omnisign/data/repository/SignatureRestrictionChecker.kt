package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.pades.SignatureFieldParameters
import eu.europa.esig.dss.pades.exception.ProtectedDocumentException
import eu.europa.esig.dss.pdf.PdfDocumentReader
import eu.europa.esig.dss.pdf.PdfPermissionsChecker

/**
 * DSS's [PdfPermissionsChecker], which reports a refusal by the document's signature restrictions as
 * a [SignatureRestrictionException].
 *
 * DSS checks two things before it signs a PDF. [checkDocumentPermissions] reads the permissions of an
 * encrypted document opened without its owner password, and [checkSignatureRestrictionDictionaries]
 * reads the certification and the locks earlier signatures set. Both refuse with the same
 * [ProtectedDocumentException], so without this checker a document locked against further signatures
 * cannot be told apart from an encrypted one. DSS skips the restriction check when it adds a
 * document timestamp, so only signing meets it.
 */
class SignatureRestrictionChecker : PdfPermissionsChecker() {

	/**
	 * Run DSS's check of the signature restrictions, and raise its refusal as a
	 * [SignatureRestrictionException] with DSS's message.
	 */
	override fun checkSignatureRestrictionDictionaries(
		documentReader: PdfDocumentReader,
		fieldParameters: SignatureFieldParameters,
	) {
		try {
			super.checkSignatureRestrictionDictionaries(documentReader, fieldParameters)
		} catch (refusal: ProtectedDocumentException) {
			throw SignatureRestrictionException(refusal.message)
		}
	}
}
