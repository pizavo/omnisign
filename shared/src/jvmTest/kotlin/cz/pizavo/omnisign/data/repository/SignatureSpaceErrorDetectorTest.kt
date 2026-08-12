package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.model.DSSException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.IOException

/**
 * Unit tests for [SignatureSpaceErrorDetector].
 *
 * The wording asserted here is PDFBox's own, taken verbatim from a real overflow observed while
 * signing with a deliberately undersized `/Contents` reservation.
 */
class SignatureSpaceErrorDetectorTest : FunSpec({

	val detector = SignatureSpaceErrorDetector()

	val pdfBoxMessage =
		"Can't write signature, not enough space; adjust it with SignatureOptions.setPreferredSignatureSize"

	test("recognises PDFBox's overflow message when DSS has wrapped it") {
		val wrapped = DSSException(
			"Unable to save a document. Reason : $pdfBoxMessage",
			IOException(pdfBoxMessage),
		)

		detector.isSignatureTooLarge(wrapped).shouldBeTrue()
	}

	test("recognises the overflow deep in the cause chain") {
		val nested = RuntimeException("outer", IllegalStateException("middle", IOException(pdfBoxMessage)))

		detector.isSignatureTooLarge(nested).shouldBeTrue()
	}

	test("does not claim unrelated failures") {
		detector.isSignatureTooLarge(IOException("Stream closed")).shouldBeFalse()
		detector.isSignatureTooLarge(DSSException("Unable to save a document. Reason : disk full")).shouldBeFalse()
	}

	test("tolerates an exception with no message anywhere in the chain") {
		detector.isSignatureTooLarge(RuntimeException(IOException())).shouldBeFalse()
	}
})
