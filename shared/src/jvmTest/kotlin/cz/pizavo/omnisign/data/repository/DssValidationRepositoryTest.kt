package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import eu.europa.esig.dss.enumerations.Indication
import eu.europa.esig.dss.enumerations.SignatureLevel as DssSignatureLevel
import eu.europa.esig.dss.i18n.MessageTag
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/**
 * Verifies the critical invariants of [DssServiceFactory] and [DssValidationRepository]
 * that protect against two past regressions:
 *
 * 1. **OJ keystore missing** — the bundled `lotl-keystore.p12` classpath resource must be
 *    present, parseable as PKCS12, and contain at least one EU signing certificate.
 *
 * 2. **Ephemeral TL cache** — the TL cache directory must be a persistent, user-specific
 *    location (not the system temp directory) and the online-loader expiration must be
 *    strictly positive so DSS can reuse cached responses between validation calls.
 *
 * It also checks the result for a document that cannot be validated and for one without
 * signatures, and that a signed document's report reads in the language it is asked for, or in
 * English for a language without a translation, even on a machine whose default locale is Czech.
 * A signed document's report also names the signing certificate's SHA-256 fingerprint.
 */
class DssValidationRepositoryTest : FunSpec({

	val repository = DssValidationRepository(
		DssServiceFactory(mockk(relaxed = true)),
		FileTrustStore(tempdir().toPath()),
		DssWarningSanitizer(),
	)
	val tmpDir = tempdir()

	fun tmpFile(name: String): File = File(tmpDir, name).also { it.createNewFile() }

	// ── OJ keystore regression ────────────────────────────────────────────────

	test("OJ keystore resource exists on the classpath") {
		val stream = DssServiceFactory::class.java
			.getResourceAsStream(DssServiceFactory.OJ_KEYSTORE_RESOURCE)
		stream shouldNotBe null
		stream!!.close()
	}

	test("OJ keystore is a valid PKCS12 openable with the expected password") {
		val stream = DssServiceFactory::class.java
			.getResourceAsStream(DssServiceFactory.OJ_KEYSTORE_RESOURCE)!!
		val source = KeyStoreCertificateSource(
			stream,
			DssServiceFactory.OJ_KEYSTORE_TYPE,
			DssServiceFactory.OJ_KEYSTORE_PASSWORD.toCharArray(),
		)
		source.certificates.shouldNotBeEmpty()
	}

	// ── TL cache regression ───────────────────────────────────────────────────

	test("TL cache expiration is strictly positive (not zero or negative)") {
		DssServiceFactory.TL_CACHE_EXPIRATION_MS shouldBeGreaterThan 0L
	}

	test("TL cache expiration is at least one hour") {
		val oneHourMs = 60 * 60 * 1000L
		DssServiceFactory.TL_CACHE_EXPIRATION_MS shouldBeGreaterThan oneHourMs
	}

	test("tlCacheDir is not the system temporary directory") {
		val cacheDir = DssServiceFactory.tlCacheDir()
		val sysTmp = System.getProperty("java.io.tmpdir")
		cacheDir.absolutePath shouldNotContain sysTmp
	}

	test("tlCacheDir path contains the omnisign application subdirectory") {
		val cacheDir = DssServiceFactory.tlCacheDir()
		cacheDir.absolutePath shouldContain "omnisign"
	}

	test("tlCacheDir path ends with the tl-cache subdirectory") {
		val cacheDir = DssServiceFactory.tlCacheDir()
		cacheDir.name shouldBe "tl-cache"
	}

	test("tlCacheDir is rooted inside the platform user-data directory") {
		val cacheDir = DssServiceFactory.tlCacheDir()
		val userHome = System.getProperty("user.home")
		val os = System.getProperty("os.name", "").lowercase()

		val expectedRoots = when {
			os.contains("win") -> listOf(
				System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local",
			)
			os.contains("mac") -> listOf("$userHome/Library/Caches")
			else -> listOf(
				System.getenv("XDG_CACHE_HOME") ?: "$userHome/.cache",
			)
		}.map { File(it).canonicalPath }

		val cacheDirCanonical = cacheDir.canonicalPath
		val isUnderExpectedRoot = expectedRoots.any { cacheDirCanonical.startsWith(it) }
		isUnderExpectedRoot shouldBe true
	}

	// ── validateDocument error handling ──────────────────────────────────────

	test("validateDocument returns ValidationFailed when the input bytes are not a valid PDF") {
		val notAPdf = tmpFile("not-a-pdf.pdf").also { it.writeText("this is not a PDF") }

		repository.validateDocument(
			ValidationParameters(
				inputBytes = notAPdf.readBytes(),
				inputName = notAPdf.name,
			)
		).shouldBeLeft().shouldBeInstanceOf<ValidationError.ValidationFailed>()
	}

	fun unsignedPdf(name: String): File = tmpFile(name).also { file ->
		PDDocument().use { document ->
			document.addPage(PDPage())
			document.save(file)
		}
	}

	test("validateDocument reports NO_SIGNATURES, not VALID, for a PDF without signatures") {
		val unsigned = unsignedPdf("unsigned.pdf")

		val report = repository.validateDocument(
			ValidationParameters(
				inputBytes = unsigned.readBytes(),
				inputName = unsigned.name,
			)
		).shouldBeRight()

		report.signatures.shouldBeEmpty()
		report.overallResult shouldBe ValidationResult.NO_SIGNATURES
	}

	test("validateDocument reports NO_SIGNATURES for a PDF without signatures when revocation is checked") {
		val unsigned = unsignedPdf("unsigned-revocation-checked.pdf")
		val config = ResolvedConfig(
			hashAlgorithm = HashAlgorithm.SHA256,
			encryptionAlgorithm = null,
			signatureLevel = SignatureLevel.PADES_BASELINE_B,
			timestampServer = null,
			ocsp = OcspConfig(),
			crl = CrlConfig(),
			validation = ValidationConfig(checkRevocation = true, useEuLotl = false),
		)

		repository.validateDocument(
			ValidationParameters(
				inputBytes = unsigned.readBytes(),
				inputName = unsigned.name,
				resolvedConfig = config,
			)
		).shouldBeRight().overallResult shouldBe ValidationResult.NO_SIGNATURES
	}

	// ── Report language ──────────────────────────────────────────────────────

	fun signedPdf(): ByteArray {
		val p12 = DssValidationRepositoryTest::class.java.getResourceAsStream("/e2e/signer.test.p12")?.readBytes()
			?: error("Missing test fixture /e2e/signer.test.p12")
		return Pkcs12SignatureToken(p12, KeyStore.PasswordProtection("test1234".toCharArray())).use { token ->
			val key = token.keys.first()
			val parameters = PAdESSignatureParameters().apply {
				signatureLevel = DssSignatureLevel.PAdES_BASELINE_B
				signingCertificate = key.certificate
				certificateChain = key.certificateChain.toList()
			}
			val service = PAdESService(CommonCertificateVerifier(true)).apply {
				setPdfObjFactory(PdfBoxNativeObjectFactory())
			}
			val document = InMemoryDocument(unsignedPdf("to-sign.pdf").readBytes())
			val signature = token.sign(service.getDataToSign(document, parameters), parameters.digestAlgorithm, key)
			service.signDocument(document, parameters, signature).openStream().use { it.readBytes() }
		}
	}

	fun dssMessage(resource: String, key: String): String = Properties().apply {
		val stream = DssValidationRepositoryTest::class.java.getResourceAsStream(resource)
			?: error("resource not found on the classpath: $resource")
		stream.reader(Charsets.UTF_8).use { load(it) }
	}.getProperty(key)

	/**
	 * A verifier with no trust anchors and no online sources, whose alerts are silenced as
	 * [DssServiceFactory.buildValidationCertificateVerifier] silences them when revocation is not
	 * checked, so that the report, rather than an exception, tells of the untrusted chain.
	 */
	fun offlineVerifier(): CommonCertificateVerifier = CommonCertificateVerifier(true).apply {
		alertOnMissingRevocationData = null
		alertOnInvalidTimestamp = null
		alertOnRevokedCertificate = null
		alertOnExpiredCertificate = null
		alertOnNotYetValidCertificate = null
		alertOnUncoveredPOE = null
		alertOnNoRevocationAfterBestSignatureTime = null
	}

	val offlineRepository = DssValidationRepository(
		mockk<DssServiceFactory>(relaxed = true).also { factory ->
			every { factory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
			every { factory.buildValidationCertificateVerifier(any(), any(), any()) } answers {
				CertificateVerifierResult(offlineVerifier())
			}
		},
		FileTrustStore(tempdir().toPath()),
		DssWarningSanitizer(),
	)

	test("on a Czech machine a report asked for in English or German reads in English, and one asked for in Czech in Czech") {
		val signed = signedPdf()
		val untrustedChain = MessageTag.BBB_XCV_CCCBB_SIG_ANS.id
		val english = LocalizableText.Literal(dssMessage("/dss-messages.properties", untrustedChain))
		val czech = LocalizableText.Literal(dssMessage("/dss-messages_cs.properties", untrustedChain))

		suspend fun errorsIn(language: String?): List<LocalizableText> =
			offlineRepository.validateDocument(
				ValidationParameters(inputBytes = signed, inputName = "signed.pdf", language = language)
			).shouldBeRight().signatures.single().errors

		val default = Locale.getDefault()
		val display = Locale.getDefault(Locale.Category.DISPLAY)
		val format = Locale.getDefault(Locale.Category.FORMAT)
		Locale.setDefault(Locale.forLanguageTag("cs-CZ"))
		try {
			errorsIn("en") shouldContain english
			errorsIn("de") shouldContain english
			errorsIn("cs") shouldContain czech
			errorsIn(null) shouldContain czech
		} finally {
			Locale.setDefault(default)
			Locale.setDefault(Locale.Category.DISPLAY, display)
			Locale.setDefault(Locale.Category.FORMAT, format)
		}
	}

	test("a signature's summary names its signing certificate's SHA-256 fingerprint, the one the full-certificate view shows") {
		val p12 = DssValidationRepositoryTest::class.java.getResourceAsStream("/e2e/signer.test.p12")?.readBytes()
			?: error("Missing test fixture /e2e/signer.test.p12")
		val signerDer = Pkcs12SignatureToken(p12, KeyStore.PasswordProtection("test1234".toCharArray())).use { token ->
			token.keys.first().certificate.encoded
		}
		val expected = MessageDigest.getInstance("SHA-256").digest(signerDer).joinToString(":") { "%02X".format(it) }

		val certificate = offlineRepository.validateDocument(
			ValidationParameters(inputBytes = signedPdf(), inputName = "signed.pdf")
		).shouldBeRight().signatures.single().certificate

		certificate.sha256Fingerprint shouldBe expected
		certificate.chain.first().details.first { it.title == "Fingerprints" }.fields
			.single { it.label == "SHA-256" }.value shouldBe expected
	}

	// ── DSS Indication mapping ──────────────────────────────────────────────

	fun mapIndication(raw: Indication): ValidationIndication = when (raw) {
		Indication.TOTAL_PASSED, Indication.PASSED -> ValidationIndication.TOTAL_PASSED
		Indication.TOTAL_FAILED, Indication.FAILED -> ValidationIndication.TOTAL_FAILED
		else -> ValidationIndication.INDETERMINATE
	}

	test("Indication.TOTAL_PASSED maps to TOTAL_PASSED") {
		mapIndication(Indication.TOTAL_PASSED) shouldBe ValidationIndication.TOTAL_PASSED
	}

	test("Indication.PASSED maps to TOTAL_PASSED") {
		mapIndication(Indication.PASSED) shouldBe ValidationIndication.TOTAL_PASSED
	}

	test("Indication.TOTAL_FAILED maps to TOTAL_FAILED") {
		mapIndication(Indication.TOTAL_FAILED) shouldBe ValidationIndication.TOTAL_FAILED
	}

	test("Indication.FAILED maps to TOTAL_FAILED") {
		mapIndication(Indication.FAILED) shouldBe ValidationIndication.TOTAL_FAILED
	}

	test("Indication.INDETERMINATE maps to INDETERMINATE") {
		mapIndication(Indication.INDETERMINATE) shouldBe ValidationIndication.INDETERMINATE
	}

	test("Indication.NO_SIGNATURE_FOUND maps to INDETERMINATE") {
		mapIndication(Indication.NO_SIGNATURE_FOUND) shouldBe ValidationIndication.INDETERMINATE
	}
})

