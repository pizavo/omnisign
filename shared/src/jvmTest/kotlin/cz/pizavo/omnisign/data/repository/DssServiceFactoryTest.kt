package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk

/**
 * Verifies [DssServiceFactory] builds TSP sources, certificate verifiers,
 * and PDF object factories with expected configuration wiring.
 */
class DssServiceFactoryTest : FunSpec({

	val credentialStore: CredentialStore = mockk(relaxed = true)
	val factory = DssServiceFactory(credentialStore)

	fun minimalConfig(checkRevocation: Boolean = true) = ResolvedConfig(
		hashAlgorithm = HashAlgorithm.SHA256,
		encryptionAlgorithm = null,
		signatureLevel = SignatureLevel.PADES_BASELINE_B,
		timestampServer = null,
		ocsp = OcspConfig(),
		crl = CrlConfig(),
		validation = ValidationConfig(checkRevocation = checkRevocation),
	)

	// ── buildTspSource ────────────────────────────────────────────────────────

	test("buildTspSource creates a source pointing to the configured URL") {
		val tsp = factory.buildTspSource(TimestampServerConfig(url = "http://tsa.example.com"))
		tsp.shouldNotBeNull()
	}

	test("buildTspSource resolves runtime password over credential store") {
		every { credentialStore.getPassword(any(), any()) } returns "stored"

		val tsp = factory.buildTspSource(
			TimestampServerConfig(
				url = "http://tsa.example.com",
				username = "user",
				runtimePassword = cz.pizavo.omnisign.domain.model.value.Sensitive("runtime"),
			)
		)
		tsp.shouldNotBeNull()
	}

	test("buildTspSource falls back to credential store when no runtime password") {
		every { credentialStore.getPassword("omnisign-tsa", "mykey") } returns "stored-pw"

		val tsp = factory.buildTspSource(
			TimestampServerConfig(
				url = "http://tsa.example.com",
				username = "user",
				credentialKey = "mykey",
			)
		)
		tsp.shouldNotBeNull()
	}

	// ── buildSigningCertificateVerifier ───────────────────────────────────────

	test("signing verifier with null config returns lenient verifier with suppressed alerts") {
		val cv = factory.buildSigningCertificateVerifier(null)
		cv.alertOnMissingRevocationData shouldBe null
		cv.alertOnUncoveredPOE shouldBe null
		cv.alertOnInvalidTimestamp shouldBe null
		cv.alertOnNoRevocationAfterBestSignatureTime shouldBe null
		cv.alertOnRevokedCertificate shouldBe null
	}

	test("signing verifier with revocation disabled returns suppressed alerts") {
		val cv = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = false))
		cv.alertOnMissingRevocationData shouldBe null
	}

	test("signing verifier with revocation enabled returns wired verifier") {
		val cv = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = true))
		cv.alertOnMissingRevocationData shouldNotBe null
		cv.aiaSource shouldNotBe null
		cv.ocspSource shouldNotBe null
		cv.crlSource shouldNotBe null
	}

	test("signing verifier injects custom alert factory") {
		val statusAlert = CollectingStatusAlert()
		val cv = factory.buildSigningCertificateVerifier(minimalConfig()) { statusAlert }
		cv.alertOnMissingRevocationData shouldBe statusAlert
	}

	// ── buildValidationCertificateVerifier ────────────────────────────────────

	test("validation verifier with null config returns suppressed alerts and empty warnings") {
		val result = factory.buildValidationCertificateVerifier(null)
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}

	test("validation verifier with revocation disabled returns suppressed alerts") {
		val result = factory.buildValidationCertificateVerifier(minimalConfig(checkRevocation = false))
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}

	test("validation verifier with revocation enabled but no TLs returns empty TL warnings") {
		val config = minimalConfig(checkRevocation = true).copy(
			validation = ValidationConfig(
				checkRevocation = true,
				useEuLotl = false,
				customTrustedLists = emptyList(),
			)
		)
		val result = factory.buildValidationCertificateVerifier(config)
		result.verifier.alertOnMissingRevocationData shouldNotBe null
		result.tlWarnings.shouldBeEmpty()
	}

	// ── buildCertificateVerifier (simple timeout-based) ──────────────────────

	test("simple certificate verifier has wired AIA, OCSP, and CRL sources") {
		val cv = factory.buildCertificateVerifier()
		cv.aiaSource shouldNotBe null
		cv.ocspSource shouldNotBe null
		cv.crlSource shouldNotBe null
	}

	// ── buildPdfObjectFactory ────────────────────────────────────────────────

	test("buildPdfObjectFactory returns a non-null PdfBox factory") {
		factory.buildPdfObjectFactory().shouldNotBeNull()
	}

	// ── companion utilities ──────────────────────────────────────────────────

	test("EU LOTL URL points to the EC trusted list endpoint") {
		DssServiceFactory.EU_LOTL_URL shouldContain "ec.europa.eu"
		DssServiceFactory.EU_LOTL_URL shouldContain "lotl"
	}
})

