package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.pades.exception.InvalidPasswordException
import eu.europa.esig.dss.pades.exception.ProtectedDocumentException
import eu.europa.esig.dss.spi.exception.IllegalInputException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.IOException

/**
 * Verifies [DocumentInputErrorDetector] recognises encrypted/protected and malformed input
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

	test("flags an IllegalInputException as malformed") {
		detector.isMalformed(IllegalInputException("not a valid PDF")).shouldBeTrue()
	}

	test("flags a malformed-input exception wrapped in a cause chain") {
		val wrapped = RuntimeException("extend failed", IllegalStateException(IllegalInputException("corrupt")))
		detector.isMalformed(wrapped).shouldBeTrue()
	}

	test("does not flag a non-input failure as malformed") {
		detector.isMalformed(ProtectedDocumentException("encrypted")).shouldBeFalse()
		detector.isMalformed(IOException("disk full")).shouldBeFalse()
	}
})
