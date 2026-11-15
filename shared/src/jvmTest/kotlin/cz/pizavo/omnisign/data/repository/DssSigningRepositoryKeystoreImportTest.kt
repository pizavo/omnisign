package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.service.DssTokenService
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.platform.PasswordCallback
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.qualified.ETSIQCObjectIdentifiers
import org.bouncycastle.asn1.x509.qualified.QCStatement
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Verifies that the certificates [DssSigningRepository.loadCertificatesFromFile] imports from a
 * PKCS#12 file, as the desktop's signing dialog does, sign a document once their alias is handed
 * back with the file as [SigningParameters.keystoreFile], and that the import lists them as
 * [DssSigningRepository.listCertificatesFromKeystore] does.
 *
 * The repository runs on a real [DssTokenService] that opens the files, with a [PasswordCallback]
 * mock in place of the password dialog and a [Pkcs11Discoverer] mock that finds no tokens.
 */
class DssSigningRepositoryKeystoreImportTest : FunSpec({

	val dir = tempdir()
	val password = "test1234"

	/**
	 * A PKCS#12 file named [fileName] holding an RSA key and a self-signed certificate for each of
	 * [subjects], distinguished names stored in that order, with serial numbers from 1 up, or all 1
	 * when [sameSerial] is set, and, when [qualified] is set, a QCStatements extension that marks
	 * each certificate as qualified and its key as held on a QSCD.
	 */
	fun keystore(fileName: String, vararg subjects: String, sameSerial: Boolean = false, qualified: Boolean = false): File {
		val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
		val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
		val now = Clock.System.now()
		subjects.forEachIndexed { index, subjectName ->
			val keyPair = generator.generateKeyPair()
			val subject = X500Name(subjectName)
			val builder = JcaX509v3CertificateBuilder(
				subject,
				BigInteger.valueOf(if (sameSerial) 1L else index + 1L),
				Date(now.minus(1.days).toEpochMilliseconds()),
				Date(now.plus(365.days).toEpochMilliseconds()),
				subject,
				keyPair.public,
			)
			if (qualified) {
				builder.addExtension(
					Extension.qCStatements,
					false,
					DERSequence(
						arrayOf<ASN1Encodable>(
							QCStatement(ETSIQCObjectIdentifiers.id_etsi_qcs_QcCompliance),
							QCStatement(ETSIQCObjectIdentifiers.id_etsi_qcs_QcSSCD),
						)
					),
				)
			}
			val certificate = JcaX509CertificateConverter()
				.getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)))
			store.setKeyEntry(subjectName, keyPair.private, password.toCharArray(), arrayOf(certificate))
		}
		return File(dir, fileName).also { file -> file.outputStream().use { store.store(it, password.toCharArray()) } }
	}

	val pair = keystore("pair.p12", "CN=OmniSign Import First", "CN=OmniSign Import Second")
	val qualified = keystore("qualified.p12", "CN=OmniSign Import Qualified", qualified = true)
	val twins = keystore(
		"twins.p12",
		"CN=OmniSign Import Twin,O=First",
		"CN=OmniSign Import Twin,O=Second",
		sameSerial = true,
	)

	val trustStore = FileTrustStore(tempdir().toPath())
	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
	every { dssServiceFactory.buildPdfObjectFactory() } answers { PdfBoxNativeObjectFactory() }
	every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(CommonCertificateVerifier())
	}

	/** A repository, the password dialog it asks and the PKCS#11 discoverer behind its token service. */
	data class Fixture(
		val repository: DssSigningRepository,
		val passwordCallback: PasswordCallback,
		val discoverer: Pkcs11Discoverer,
	)

	/** A [Fixture] whose password dialog answers every prompt with [answer]. */
	fun fixture(answer: String? = password): Fixture {
		val passwordCallback = mockk<PasswordCallback>()
		every { passwordCallback.requestPassword(any(), any()) } returns answer
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.discoveryRunning } returns MutableStateFlow(false)
		every { discoverer.getCachedTokens() } returns emptyList()
		val repository = DssSigningRepository(
			DssTokenService(passwordCallback, pkcs11Discoverer = discoverer), mockk(), mockk(relaxed = true),
			dssServiceFactory, AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
			trustStore, DocumentInputErrorDetector(), Pkcs11SessionCache(), SignatureSpaceErrorDetector(),
		)
		return Fixture(repository, passwordCallback, discoverer)
	}

	/** A configuration that signs at PAdES-B. */
	val config: ResolvedConfig = ResolvedConfig.resolve(
		GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B),
		profile = null,
		operationOverrides = null,
	).shouldBeRight()

	/** A one-page PDF with nothing on it. */
	fun plainPdf(): ByteArray {
		val out = ByteArrayOutputStream()
		PDDocument().use { document ->
			document.addPage(PDPage())
			document.save(out)
		}
		return out.toByteArray()
	}

	test("a certificate imported from a file signs once its alias comes back with the file, which asks for the password again") {
		val (repository, passwordCallback, discoverer) = fixture()

		val imported = repository.loadCertificatesFromFile(pair.absolutePath).shouldBeRight()
		imported shouldHaveSize 2
		imported.forEach {
			it.tokenType shouldBe TokenType.FILE.name
			it.tokenName shouldBe pair.name
		}
		val second = imported.single { it.subjectDN == "CN=OmniSign Import Second" }

		val result = repository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = second.alias,
				keystoreFile = pair.absolutePath,
				addTimestamp = false,
				resolvedConfig = config,
			)
		).shouldBeRight()

		val signature = PDFDocumentValidator(InMemoryDocument(result.outputBytes))
			.apply { setCertificateVerifier(CommonCertificateVerifier()) }
			.signatures
			.single()
		signature.signatureCryptographicVerification.isSignatureValid.shouldBeTrue()
		signature.signingCertificateToken.certificate.subjectX500Principal.name shouldBe "CN=OmniSign Import Second"
		verify(exactly = 2) { passwordCallback.requestPassword(any(), any()) }
		verify(exactly = 0) { discoverer.getCachedTokens() }
	}

	test("of two certificates a file holds under one alias, the one the import lists first is the one that signs") {
		val (repository) = fixture()

		val imported = repository.loadCertificatesFromFile(twins.absolutePath).shouldBeRight()
		imported shouldHaveSize 2
		val alias = imported.map { it.alias }.distinct().single()
		imported.first().subjectDN shouldContain "O=First"

		val result = repository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = alias,
				keystoreFile = twins.absolutePath,
				addTimestamp = false,
				resolvedConfig = config,
			)
		).shouldBeRight()

		val signature = PDFDocumentValidator(InMemoryDocument(result.outputBytes))
			.apply { setCertificateVerifier(CommonCertificateVerifier()) }
			.signatures
			.single()
		signature.signatureCryptographicVerification.isSignatureValid.shouldBeTrue()
		signature.signingCertificateToken.certificate.subjectX500Principal.name shouldContain "O=First"
	}

	test("the import lists a file's certificates as the keystore listing does, qualified flags included") {
		val (repository) = fixture()

		val imported = repository.loadCertificatesFromFile(qualified.absolutePath).shouldBeRight()
		val listed = repository.listCertificatesFromKeystore(qualified.absolutePath, Sensitive(password)).shouldBeRight()

		imported shouldBe listed
		imported.single().isQualified shouldBe true
		imported.single().isQscd shouldBe true
	}

	test("a file that does not exist is refused before the password is asked for") {
		val (repository, passwordCallback) = fixture()
		val missing = File(dir, "missing.p12")

		val error = repository.loadCertificatesFromFile(missing.absolutePath).shouldBeLeft()

		error.shouldBeInstanceOf<SigningError.TokenAccessError>().text shouldBe
			LocalizableText.of(MessageKey.SIGNING_FILE_NOT_FOUND, missing.path)
		verify(exactly = 0) { passwordCallback.requestPassword(any(), any()) }
	}

	test("a cancelled password prompt imports nothing") {
		val (repository) = fixture(answer = null)

		val error = repository.loadCertificatesFromFile(pair.absolutePath).shouldBeLeft()

		error.shouldBeInstanceOf<SigningError.TokenAccessError>().text shouldBe
			LocalizableText.of(MessageKey.SIGNING_PASSWORD_ENTRY_CANCELLED)
	}
})
