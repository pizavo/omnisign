package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.pades.exception.InvalidPasswordException
import eu.europa.esig.dss.pades.exception.ProtectedDocumentException
import eu.europa.esig.dss.spi.exception.IllegalInputException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.IOException

/**
 * Verifies [DocumentInputErrorDetector] recognises encrypted/protected, locked and malformed input
 * documents — directly and when wrapped in a cause chain — and leaves unrelated failures alone.
 */
class DocumentInputErrorDetectorTest : FunSpec({

	val detector = DocumentInputErrorDetector()

	test("flags a ProtectedDocumentException as encrypted") {
		detector.isEncrypted(ProtectedDocumentException("the document is encrypted")).shouldBeTrue()
	}

	test("flags an InvalidPasswordException as encrypted") {
		detector.isEncrypted(InvalidPasswordException("wrong password")).shouldBeTrue()
	}

	test("flags an encrypted-document exception wrapped in a cause chain") {
		val wrapped = RuntimeException("extend failed", ProtectedDocumentException("protected"))
		detector.isEncrypted(wrapped).shouldBeTrue()
	}

	test("does not flag a non-encryption failure as encrypted") {
		detector.isEncrypted(IllegalInputException("not a pdf")).shouldBeFalse()
		detector.isEncrypted(IOException("disk full")).shouldBeFalse()
	}

	test("does not flag a signature restriction as encrypted, though it is a ProtectedDocumentException") {
		detector.isEncrypted(SignatureRestrictionException("DocMDP dictionary")).shouldBeFalse()
		detector.isEncrypted(RuntimeException("signing failed", SignatureRestrictionException("DocMDP dictionary")))
			.shouldBeFalse()
	}

	test("flags a signature restriction as locked, directly and wrapped in a cause chain") {
		detector.isLocked(SignatureRestrictionException("DocMDP dictionary")).shouldBeTrue()
		detector.isLocked(RuntimeException("signing failed", SignatureRestrictionException("Lock dictionary")))
			.shouldBeTrue()
	}

	test("does not flag encryption or an unrelated failure as locked") {
		detector.isLocked(ProtectedDocumentException("the document is encrypted")).shouldBeFalse()
		detector.isLocked(InvalidPasswordException("wrong password")).shouldBeFalse()
		detector.isLocked(IOException("disk full")).shouldBeFalse()
	}

	test("looksLikePdf accepts a document whose %PDF- header is at the start") {
		detector.looksLikePdf("%PDF-1.7\nbody".encodeToByteArray()).shouldBeTrue()
	}

	test("looksLikePdf accepts input that is the %PDF- header alone") {
		detector.looksLikePdf("%PDF-".encodeToByteArray()).shouldBeTrue()
	}

	test("looksLikePdf accepts a header within the first kilobyte") {
		val padded = ByteArray(500) { ' '.code.toByte() } + "%PDF-1.4".encodeToByteArray()
		detector.looksLikePdf(padded).shouldBeTrue()
	}

	test("looksLikePdf rejects input with no PDF header") {
		detector.looksLikePdf("just some text, not a pdf".encodeToByteArray()).shouldBeFalse()
		detector.looksLikePdf(ByteArray(0)).shouldBeFalse()
	}
})
