package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.repository.DssWarningSanitizer.WarningCategory
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.spi.client.http.DataLoader
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.validation.SignatureValidationContext
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Checks [DssWarningSanitizer] against the line DSS itself logs when it cannot download a certificate's CRL, so that
 * the wording the sanitizer looks for is DSS's own rather than a copy typed into a test.
 *
 * Each test validates the committed end-to-end signer certificate with the committed CA as its trust anchor, an
 * online CRL source whose data loader fails every download, and no OCSP source, so DSS asks for the CRL the
 * certificate names and gives up. A [DssLogCapture] session records what DSS logs meanwhile, as one does around every
 * signing and extension.
 */
class DssCrlFailureLogTest : FunSpec({

	val pass = "test1234".toCharArray()

	/**
	 * Read the certificate of the committed end-to-end PKCS#12 [fixture].
	 */
	fun certificate(fixture: String): X509Certificate {
		val keyStore = KeyStore.getInstance("PKCS12")
		DssCrlFailureLogTest::class.java.getResourceAsStream("/e2e/$fixture")
			?.use { keyStore.load(it, pass) }
			?: error("Missing test fixture /e2e/$fixture")
		return keyStore.getCertificate(keyStore.aliases().nextElement()) as X509Certificate
	}

	val signer = CertificateToken(certificate("signer.test.p12"))
	val ca = CertificateToken(certificate("ca.test.p12"))

	/** The categories signing hides, as `DssSigningRepository` lists them. */
	val signingSuppressed = setOf(
		WarningCategory.REVOCATION_NOT_FOUND,
		WarningCategory.FRESH_REVOCATION_MISSING,
		WarningCategory.CERTIFICATE_PARSE_ERROR,
	)

	/**
	 * Validate the signer certificate as the spec describes and return the messages the [DssLogCapture] session
	 * recorded, adding each address DSS asked the data loader for to [requested].
	 */
	fun loggedWhileFetchingCrl(requested: MutableList<String>): List<String> {
		val unreachable = object : DataLoader by CommonsDataLoader() {
			override fun get(url: String): ByteArray {
				requested += url
				throw DSSExternalResourceException("$url cannot be reached from this test")
			}
		}
		val verifier = CommonCertificateVerifier(true).apply {
			crlSource = OnlineCRLSource(unreachable)
			setTrustedCertSources(CommonTrustedCertificateSource().apply { addCertificate(ca) })
		}
		val context = SignatureValidationContext().apply {
			initialize(verifier)
			addCertificateTokenForVerification(signer)
		}
		val session = DssLogCapture().start()
		try {
			context.validate()
			return session.stop()
		} finally {
			session.stop()
		}
	}

	test("the line DSS logs for a CRL it could not download names the certificate and is classified as missing revocation data") {
		val requested = mutableListOf<String>()
		val logged = loggedWhileFetchingCrl(requested)

		requested shouldHaveSize 1
		val failure = logged.single { signer.dssIdAsString in it && requested.single() in it }
		DssWarningSanitizer().classify(failure) shouldBe
				(WarningCategory.REVOCATION_NOT_FOUND to setOf(signer.dssIdAsString))
	}

	test("signing shows nothing of the CRL DSS could not download, and extending reports it in the revocation summary") {
		val logged = loggedWhileFetchingCrl(mutableListOf())
		val sanitizer = DssWarningSanitizer()

		val signing = sanitizer.sanitize(logged, suppressedCategories = signingSuppressed)
		signing.summaries.shouldBeEmpty()

		val extending = sanitizer.sanitize(logged)
		extending.summaries shouldHaveSize 1
		extending.summaries[0] shouldContain "CRL/OCSP"
		extending.summaries[0] shouldContain "1 certificate"
		extending.longTermMaterialMissing shouldBe true
	}
})
