package cz.pizavo.omnisign.e2e

import arrow.core.right
import cz.pizavo.omnisign.data.repository.*
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.service.pkcs11CertAlias
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.InstantFormatter
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.spi.DSSUtils
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.TimeZone
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.text.PDFTextStripper
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.imageio.ImageIO

/**
 * End-to-end checks of what a visible signature shows, read back from the signed PDF.
 *
 * DSS draws a visible signature only when it is given text or an image, so a signature asked for
 * with a box alone used to come out invisible. Each test signs a one-page PDF at B-B and reads the
 * text the signature's appearance draws. PDFBox extracts no text from an annotation's appearance,
 * so the appearance is drawn on a page of its own first, and its text is extracted from that page.
 */
class VisibleSignatureE2ETest : FunSpec({

	val tmp = tempdir()
	val pass = "test1234"

	/** The test fixture [name], copied into a temporary file. */
	fun fixture(name: String): File {
		val bytes = VisibleSignatureE2ETest::class.java.getResourceAsStream("/e2e/$name")?.readBytes()
			?: error("Missing test fixture /e2e/$name")
		return File(tmp, name).also { it.writeBytes(bytes) }
	}

	val signerP12 = fixture("signer.test.p12")
	val signerCert = KeyStore.getInstance("PKCS12").run {
		signerP12.inputStream().use { load(it, pass.toCharArray()) }
		getCertificate(aliases().nextElement()) as X509Certificate
	}

	val caKeyStore = KeyStore.getInstance("PKCS12").apply {
		fixture("ca.test.p12").inputStream().use { load(it, pass.toCharArray()) }
	}
	val caAlias = caKeyStore.aliases().nextElement()
	val caKey = caKeyStore.getKey(caAlias, pass.toCharArray()) as PrivateKey
	val caCert = caKeyStore.getCertificate(caAlias) as X509Certificate
	val pki = TestPki(caCert, caKey)

	val crl = run {
		val now = Date()
		val builder = X509v2CRLBuilder(JcaX509CertificateHolder(caCert).subject, Date(now.time - DAY_MILLIS))
		builder.setNextUpdate(Date(now.time + DAY_MILLIS * 3650))
		val contentSigner = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(caKey)
		JcaX509CRLConverter().setProvider("BC").getCRL(builder.build(contentSigner))
	}

	/** A verifier that trusts the test CA and holds its CRL, so the signing chain is checked offline. */
	fun verifier(): CommonCertificateVerifier =
		CommonCertificateVerifier().apply {
			setTrustedCertSources(
				CommonTrustedCertificateSource().apply { addCertificate(DSSUtils.loadCertificate(caCert.encoded)) },
			)
			crlSource = ExternalResourcesCRLSource(InMemoryDocument(crl.encoded))
			isCheckRevocationForUntrustedChains = false
			alertOnMissingRevocationData = null
			alertOnNoRevocationAfterBestSignatureTime = null
		}

	val configRepository = mockk<ConfigRepository>()
	coEvery { configRepository.getCurrentConfig() } returns AppConfig(global = GlobalConfig())

	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
	every { dssServiceFactory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
	every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(verifier())
	}

	val trustStore = FileTrustStore(tempdir().toPath())

	/** A one-page PDF with nothing on its page. */
	fun plainPdf(): ByteArray {
		val out = ByteArrayOutputStream()
		PDDocument().use { document ->
			document.addPage(PDPage())
			document.save(out)
		}
		return out.toByteArray()
	}

	/** A signing token over the PKCS#12 file [p12], as the token service hands one out. */
	fun signingToken(p12: File): SigningToken {
		val token = Pkcs12SignatureToken(p12, KeyStore.PasswordProtection(pass.toCharArray()))
		return object : SigningToken {
			override fun getDssToken(): Any = token
			override fun close() = token.close()
		}
	}

	/**
	 * Sign [plainPdf] at B-B with the key in the PKCS#12 file [p12], whose certificate is
	 * [certificate], drawing the signature as [visible] asks.
	 */
	suspend fun sign(p12: File, certificate: X509Certificate, visible: VisibleSignatureParameters): ByteArray {
		val token = TokenInfo(
			id = "visible-${p12.name}", name = p12.name, type = TokenType.FILE,
			path = p12.absolutePath, requiresPin = false,
		)
		val entry = CertificateEntry(
			alias = pkcs11CertAlias(certificate, token),
			subjectDN = certificate.subjectX500Principal.toString(),
			issuerDN = certificate.issuerX500Principal.toString(),
			serialNumber = certificate.serialNumber.toString(),
			validFrom = certificate.notBefore.toKotlinInstant(),
			validTo = certificate.notAfter.toKotlinInstant(),
			keyUsages = emptyList(),
			tokenInfo = token,
		)
		val tokenService = mockk<TokenService>()
		coEvery { tokenService.discoverTokens() } returns listOf(token).right()
		coEvery { tokenService.probeTokenPresent(any()) } returns true
		coEvery { tokenService.loadCertificatesSilent(any(), any()) } returns listOf(entry).right()
		coEvery { tokenService.getSigningToken(any(), any()) } answers { signingToken(p12).right() }
		coEvery { tokenService.openSigningToken(any(), any()) } answers { signingToken(p12).right() }
		val repository = DssSigningRepository(
			tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
			AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(), trustStore,
			DocumentInputErrorDetector(), Pkcs11SessionCache(), SignatureSpaceErrorDetector(), DssLogCapture(),
		)
		val level = SignatureLevel.PADES_BASELINE_B
		return repository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = entry.alias,
				signatureLevel = level,
				addTimestamp = false,
				visibleSignature = visible,
				resolvedConfig = ResolvedConfig.resolve(
					global = GlobalConfig(defaultSignatureLevel = level),
					profile = null,
					operationOverrides = null,
				).getOrNull()!!,
			)
		).shouldBeRight().outputBytes
	}

	/**
	 * The text the appearance of [pdf]'s only signature draws, its runs of whitespace joined into
	 * single spaces, or an empty string when the signature has no appearance.
	 */
	fun appearanceText(pdf: ByteArray): String = Loader.loadPDF(pdf).use { document ->
		val appearance = document.signatureFields.single().widgets.single().normalAppearanceStream
			?: return@use ""
		val page = PDPage()
		document.addPage(page)
		PDPageContentStream(document, page).use { it.drawForm(appearance) }
		PDFTextStripper().apply { startPage = document.numberOfPages }.getText(document)
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	/** The signing time [pdf]'s only signature claims, as the label shows it. */
	fun claimedSigningTime(pdf: ByteArray): String = Loader.loadPDF(pdf).use { document ->
		val signingTime = document.lastSignatureDictionary.signDate.shouldNotBeNull().time.toKotlinInstant()
		InstantFormatter.formatDateTime(signingTime, TimeZone.currentSystemDefault(), DateFormat.ISO_8601)
	}

	/** The common name of [certificate]'s subject, read with BouncyCastle rather than the code under test. */
	fun commonName(certificate: X509Certificate): String =
		IETFUtils.valueToString(
			X500Name.getInstance(certificate.subjectX500Principal.encoded).getRDNs(BCStyle.CN).single().first.value,
		)

	val box = VisibleSignatureParameters(page = 1, x = 50f, y = 50f, width = 200f, height = 50f)

	test("a signature asked for with a box alone shows who signed and when, in a box of the size asked for") {
		val signed = sign(signerP12, signerCert, box)

		appearanceText(signed) shouldBe "Digitally signed by ${commonName(signerCert)} Date: ${claimedSigningTime(signed)}"
		Loader.loadPDF(signed).use { document ->
			val rectangle = document.signatureFields.single().widgets.single().rectangle
			rectangle.width shouldBe 200f
			rectangle.height shouldBe 50f
		}
	}

	test("a signer whose name DSS's font cannot draw still signs, and the box leaves the name out") {
		val leaf = pki.issueLeaf(
			commonName = "Γιάννης Παπαδόπουλος",
			notBefore = Date(System.currentTimeMillis() - DAY_MILLIS),
			notAfter = Date(System.currentTimeMillis() + DAY_MILLIS * 3650),
		)
		val p12 = File(tmp, "greek-signer.p12").also { it.writeBytes(leaf.p12) }

		val signed = sign(p12, leaf.certificate, box)

		appearanceText(signed) shouldBe "Digitally signed Date: ${claimedSigningTime(signed)}"
	}

	test("a signature given a text shows that text instead of the label") {
		val signed = sign(signerP12, signerCert, box.copy(text = "Approved by the faculty"))

		appearanceText(signed) shouldBe "Approved by the faculty"
	}

	test("a signature given an image alone shows the image without a label") {
		val logo = File(tmp, "logo.png").also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }

		val signed = sign(signerP12, signerCert, box.copy(imagePath = logo.absolutePath))

		appearanceText(signed) shouldBe ""
		Loader.loadPDF(signed).use { document ->
			document.signatureFields.single().widgets.single().normalAppearanceStream.shouldNotBeNull()
		}
	}
}) {
	private companion object {
		/** A day, in milliseconds. */
		const val DAY_MILLIS = 86_400_000L
	}
}
