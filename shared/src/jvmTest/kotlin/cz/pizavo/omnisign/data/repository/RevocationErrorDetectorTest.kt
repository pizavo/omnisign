package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.model.DSSException
import eu.europa.esig.dss.spi.exception.DSSDataLoaderMultipleException
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.IOException

/**
 * Verifies [RevocationErrorDetector] recognises revocation-data failures: a typed
 * [DSSExternalResourceException] (and its multi-loader subclass), the same wrapped deep in a cause
 * chain, and the bare-`DSSException` keyword fallback — while leaving unrelated failures alone.
 */
class RevocationErrorDetectorTest : FunSpec({

	val detector = RevocationErrorDetector()

	test("flags a DSSExternalResourceException directly") {
		detector.isRevocationException(DSSExternalResourceException("OCSP request failed")).shouldBeTrue()
	}

	test("flags a DSSDataLoaderMultipleException when every CRL/OCSP endpoint failed") {
		val ex = DSSDataLoaderMultipleException(mapOf<String, Throwable>("http://crl.example/x" to IOException("refused")))
		detector.isRevocationException(ex).shouldBeTrue()
	}

	test("flags a DSSExternalResourceException wrapped deep in a cause chain") {
		val wrapped = RuntimeException("extend failed", IllegalStateException(DSSExternalResourceException("no response")))
		detector.isRevocationException(wrapped).shouldBeTrue()
	}

	test("flags a bare DSSException whose message mentions revocation (keyword fallback)") {
		detector.isRevocationException(DSSException("No revocation data found for the signing certificate")).shouldBeTrue()
	}

	test("flags a bare DSSException mentioning CRL or OCSP") {
		detector.isRevocationException(DSSException("Unable to retrieve the CRL")).shouldBeTrue()
		detector.isRevocationException(DSSException("OCSP responder unreachable")).shouldBeTrue()
	}

	test("does not flag an unrelated failure") {
		detector.isRevocationException(IOException("disk full")).shouldBeFalse()
	}

	test("does not flag a generic DSSException without revocation wording") {
		detector.isRevocationException(DSSException("Unable to build the signature")).shouldBeFalse()
	}
})
