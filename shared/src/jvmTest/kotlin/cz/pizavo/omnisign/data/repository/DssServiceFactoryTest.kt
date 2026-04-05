package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

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
		validation = ValidationConfig(checkRevocation = checkRevocation, useEuLotl = false),
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
		val result = factory.buildSigningCertificateVerifier(null)
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.verifier.alertOnUncoveredPOE shouldBe null
		result.verifier.alertOnInvalidTimestamp shouldBe null
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldBe null
		result.verifier.alertOnRevokedCertificate shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}
	
	test("signing verifier with revocation disabled returns suppressed alerts") {
		val result = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = false))
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}
	
	test("signing verifier with revocation enabled returns wired verifier") {
		val result = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = true))
		result.verifier.aiaSource shouldNotBe null
		result.verifier.ocspSource shouldNotBe null
		result.verifier.crlSource shouldNotBe null
		result.verifier.isCheckRevocationForUntrustedChains shouldBe false
	}
	
	test("signing verifier suppresses missing-revocation and fresh-revocation alerts") {
		val result = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = true))
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldBe null
	}
	
	test("signing verifier keeps actionable alerts active") {
		val statusAlert = CollectingStatusAlert()
		val result = factory.buildSigningCertificateVerifier(minimalConfig()) { statusAlert }
		result.verifier.alertOnUncoveredPOE shouldBe statusAlert
		result.verifier.alertOnInvalidTimestamp shouldBe statusAlert
		result.verifier.alertOnRevokedCertificate shouldBe statusAlert
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
	
	// ── buildDirectTrustedCertSource ─────────────────────────────────────────
	
	test("buildDirectTrustedCertSource returns null for empty list") {
		DssServiceFactory.buildDirectTrustedCertSource(emptyList()) shouldBe null
	}
	
	test("buildDirectTrustedCertSource builds source from Base64 DER certs") {
		val selfSigned = generateSelfSignedCert()
		val base64 = Base64.getEncoder().encodeToString(selfSigned.encoded)
		val cert = TrustedCertificateConfig(
			name = "test-ca",
			type = TrustedCertificateType.ANY,
			certificateBase64 = base64,
			subjectDN = selfSigned.subjectX500Principal.name,
		)
		val source = DssServiceFactory.buildDirectTrustedCertSource(listOf(cert))
		source.shouldNotBeNull()
		source.certificates shouldHaveSize 1
	}
	
	test("signing verifier with trusted certs has wired trusted source") {
		val selfSigned = generateSelfSignedCert()
		val base64 = Base64.getEncoder().encodeToString(selfSigned.encoded)
		val config = minimalConfig().copy(
			validation = ValidationConfig(
				useEuLotl = false,
				trustedCertificates = listOf(
					TrustedCertificateConfig("test-ca", TrustedCertificateType.ANY, base64, "CN=Test")
				)
			)
		)
		val result = factory.buildSigningCertificateVerifier(config)
		result.verifier.trustedCertSources.shouldNotBeNull()
		result.verifier.trustedCertSources.numberOfCertificates shouldBe 1
	}
}) {
	companion object {
		/**
		 * Generate a self-signed X.509 certificate for testing purposes.
		 */
		fun generateSelfSignedCert(): java.security.cert.X509Certificate {
			val keyPairGen = java.security.KeyPairGenerator.getInstance("RSA")
			keyPairGen.initialize(2048)
			val keyPair = keyPairGen.generateKeyPair()
			
			val subject = org.bouncycastle.asn1.x500.X500Name("CN=Test")
			val serial = java.math.BigInteger.valueOf(System.currentTimeMillis())
			val now = Clock.System.now()
			val notBefore = Date.from(now.toJavaInstant())
			val notAfter = Date.from((now + 365.days).toJavaInstant())
			
			val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
				subject, serial, notBefore, notAfter, subject, keyPair.public
			)
			val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
				.build(keyPair.private)
			val holder = builder.build(signer)
			
			return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
				.getCertificate(holder)
		}
	}
}

