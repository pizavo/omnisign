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
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.model.result.RenewalReason
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.DSSUtils
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.*

/**
 * Repository-level end-to-end test of the real archiving pipeline at the **long-term** PAdES levels
 * (B-LT and B-LTA) against a committed throwaway PKI and an in-process RFC 3161 TSA.
 *
 * It drives the production [DssArchivingRepository] (using [DssSigningRepository] to produce the B-T
 * input) with real DSS components and an injected offline CRL + trusted CA, so extension embeds
 * revocation data and adds an archival timestamp with no network. The bare PAdES-B and B-T tiers are
 * covered end-to-end through the CLI in `:cli`'s `CliSigningPipelineE2ETest`; here, signing B-T is
 * only the setup that produces the document the long-term tests extend.
 */
class SigningPipelineE2ETest : FunSpec({

	val tmp = tempdir()
	val pass = "test1234"

	fun fixture(name: String): File {
		val bytes = SigningPipelineE2ETest::class.java.getResourceAsStream("/e2e/$name")?.readBytes()
			?: error("Missing test fixture /e2e/$name")
		return File(tmp, name).also { it.writeBytes(bytes) }
	}

	val signerP12 = fixture("signer.test.p12")
	val tsa = LocalTestTsa.start(fixture("tsa.test.p12").readBytes(), pass)
	afterSpec { tsa.close() }

	val signerKeyStore = KeyStore.getInstance("PKCS12").apply {
		signerP12.inputStream().use { load(it, pass.toCharArray()) }
	}
	val signerCert = signerKeyStore.getCertificate(signerKeyStore.aliases().nextElement()) as X509Certificate

	val fileToken = TokenInfo(
		id = "e2e-file", name = "signer.test.p12", type = TokenType.FILE,
		path = signerP12.absolutePath, requiresPin = false,
	)
	val certEntry = CertificateEntry(
		alias = pkcs11CertAlias(signerCert, fileToken),
		subjectDN = signerCert.subjectX500Principal.toString(),
		issuerDN = signerCert.issuerX500Principal.toString(),
		serialNumber = signerCert.serialNumber.toString(),
		validFrom = signerCert.notBefore.toKotlinInstant(),
		validTo = signerCert.notAfter.toKotlinInstant(),
		keyUsages = emptyList(),
		tokenInfo = fileToken,
	)

	val caKeyStore = KeyStore.getInstance("PKCS12").apply {
		fixture("ca.test.p12").inputStream().use { load(it, pass.toCharArray()) }
	}
	val caAlias = caKeyStore.aliases().nextElement()
	val caKey = caKeyStore.getKey(caAlias, pass.toCharArray()) as PrivateKey
	val caCert = caKeyStore.getCertificate(caAlias) as X509Certificate

	// A CA-signed CRL with no revoked entries, generated fresh (so it never fails a freshness check),
	// supplied to DSS as an offline source so B-LT/B-LTA extension can embed revocation data without
	// any network: the test certificates carry no CRL-DP/AIA, so DSS cannot fetch it otherwise.
	val crl: X509CRL = run {
		val now = Date()
		val builder = X509v2CRLBuilder(JcaX509CertificateHolder(caCert).subject, Date(now.time - 86_400_000L))
		builder.setNextUpdate(Date(now.time + 86_400_000L * 3650))
		val contentSigner = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(caKey)
		JcaX509CRLConverter().setProvider("BC").getCRL(builder.build(contentSigner))
	}

	fun trustAndRevocationVerifier(): CommonCertificateVerifier =
		CommonCertificateVerifier().apply {
			setTrustedCertSources(
				CommonTrustedCertificateSource().apply { addCertificate(DSSUtils.loadCertificate(caCert.encoded)) },
			)
			crlSource = ExternalResourcesCRLSource(InMemoryDocument(crl.encoded))
			isCheckRevocationForUntrustedChains = false
			alertOnMissingRevocationData = null
			alertOnUncoveredPOE = null
			alertOnInvalidTimestamp = null
			alertOnNoRevocationAfterBestSignatureTime = null
			alertOnRevokedCertificate = null
		}

	/**
	 * Mirrors [DssServiceFactory.buildExtendCertificateVerifier]: the augmentation path keeps the two
	 * revocation alerts active, wired to the alert the repository supplies, so that an extension
	 * which embeds no revocation data is observable here exactly as it is in production.
	 */
	fun extendVerifier(alert: StatusAlert): CommonCertificateVerifier =
		trustAndRevocationVerifier().apply {
			alertOnMissingRevocationData = alert
			alertOnNoRevocationAfterBestSignatureTime = alert
		}

	/**
	 * The CRLs and OCSP responses actually present in the output document's DSS dictionary. Reading
	 * the contents rather than the dictionary's presence is what tells a real B-LT promotion from a
	 * dictionary holding certificates alone.
	 */
	fun embeddedRevocationCount(pdf: ByteArray): Int =
		PDFDocumentValidator(InMemoryDocument(pdf))
			.apply { setCertificateVerifier(CommonCertificateVerifier()) }
			.dssDictionaries
			.sumOf { it.crLs.size + it.ocsPs.size }

	fun realSigningToken(): SigningToken {
		val token = Pkcs12SignatureToken(signerP12, KeyStore.PasswordProtection(pass.toCharArray()))
		return object : SigningToken {
			override fun getDssToken(): Any = token
			override fun close() = token.close()
		}
	}

	val tokenService = mockk<TokenService>()
	coEvery { tokenService.discoverTokens() } returns listOf(fileToken).right()
	coEvery { tokenService.probeTokenPresent(any()) } returns true
	coEvery { tokenService.loadCertificatesSilent(any(), any()) } returns listOf(certEntry).right()
	coEvery { tokenService.getSigningToken(any(), any()) } answers { realSigningToken().right() }
	coEvery { tokenService.openSigningToken(any(), any()) } answers { realSigningToken().right() }

	val configRepository = mockk<ConfigRepository>()
	coEvery { configRepository.getCurrentConfig() } returns AppConfig(global = GlobalConfig())

	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
	every { dssServiceFactory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
	every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(trustAndRevocationVerifier())
	}
	every { dssServiceFactory.buildExtendCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(extendVerifier(arg<() -> StatusAlert>(2)()))
	}
	every { dssServiceFactory.buildLevelInspectionVerifier(any(), any()) } answers {
		CertificateVerifierResult(trustAndRevocationVerifier())
	}
	every { dssServiceFactory.buildTspSource(any()) } returns
		OnlineTSPSource(tsa.url).apply { setDataLoader(TimestampDataLoader()) }

	val signingRepository = DssSigningRepository(
		tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), Pkcs11SessionCache(), SignatureSpaceErrorDetector(),
	)
	val archivingRepository = DssArchivingRepository(
		configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
		RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
		mockk<RenewalCheckCache>(relaxed = true), SignatureSpaceErrorDetector(),
	)

	fun plainPdf(): ByteArray {
		val out = ByteArrayOutputStream()
		org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
			doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
			doc.save(out)
		}
		return out.toByteArray()
	}

	fun resolvedConfig(global: GlobalConfig): ResolvedConfig =
		ResolvedConfig.resolve(global = global, profile = null, operationOverrides = null).getOrNull()!!

	suspend fun signBaselineT(): ByteArray =
		signingRepository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = certEntry.alias,
				signatureLevel = SignatureLevel.PADES_BASELINE_T,
				addTimestamp = true,
				resolvedConfig = resolvedConfig(
					GlobalConfig(
						defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T,
						timestampServer = TimestampServerConfig(url = tsa.url),
					),
				),
			)
		).shouldBeRight().outputBytes

	test("extends a signed PDF to PAdES-B-LT, embedding offline revocation data") {
		val signed = signBaselineT()

		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signed,
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LT,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()

		archivingRepository.getDocumentTimestampInfo(extended.outputBytes).shouldBeRight().containsLtData.shouldBeTrue()
		embeddedRevocationCount(extended.outputBytes) shouldBeGreaterThan 0
		extended.revocationDataMissing.shouldBeFalse()
		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_LT
		extended.newSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT.name
	}

	test("extends a signed PDF to PAdES-B-LTA, adding an archival document timestamp over the LT data") {
		val signed = signBaselineT()

		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signed,
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LTA,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()

		val info = archivingRepository.getDocumentTimestampInfo(extended.outputBytes).shouldBeRight()
		info.containsLtData.shouldBeTrue()
		info.hasDocumentTimestamp.shouldBeTrue()
		embeddedRevocationCount(extended.outputBytes) shouldBeGreaterThan 0
		extended.revocationDataMissing.shouldBeFalse()
		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("a B-T document is due for preservation now, on the signing certificate's clock") {
		val signed = File(tmp, "b-t-renewal.pdf").also { it.writeBytes(signBaselineT()) }

		val assessment = archivingRepository.needsArchivalRenewal(signed.absolutePath).shouldBeRight()

		assessment.need shouldBe RenewalNeed.NEEDED
		assessment.reason shouldBe RenewalReason.BELOW_LT
		assessment.dueAt shouldBe signerCert.notAfter.toKotlinInstant()
	}

	test("a B-LT document whose revocation data predates its timestamp is refreshed before sealing") {
		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signBaselineT(),
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LT,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()
		val file = File(tmp, "b-lt-renewal.pdf").also { it.writeBytes(extended.outputBytes) }

		val assessment = archivingRepository.needsArchivalRenewal(file.absolutePath).shouldBeRight()

		assessment.need shouldBe RenewalNeed.NEEDED
		assessment.reason shouldBe RenewalReason.LT_REFRESH_NEEDED
	}

	test("a freshly sealed B-LTA document is not due for anything") {
		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signBaselineT(),
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LTA,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()
		val file = File(tmp, "b-lta-renewal.pdf").also { it.writeBytes(extended.outputBytes) }

		archivingRepository.needsArchivalRenewal(file.absolutePath).shouldBeRight()
			.need shouldBe RenewalNeed.NOT_NEEDED
	}

	test("a document timestamp over no validation data is reported as B-T, not B-LTA") {
		val signed = signingRepository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = certEntry.alias,
				signatureLevel = SignatureLevel.PADES_BASELINE_B,
				addTimestamp = false,
				resolvedConfig = resolvedConfig(GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B)),
			)
		).shouldBeRight().outputBytes

		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signed,
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_T,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()

		val info = archivingRepository.getDocumentTimestampInfo(extended.outputBytes).shouldBeRight()
		info.hasDocumentTimestamp.shouldBeTrue()
		embeddedRevocationCount(extended.outputBytes) shouldBe 0
		info.level shouldBe SignatureLevel.PADES_BASELINE_T
		info.containsLtData.shouldBeFalse()
	}

	test("reports the level the document actually reached, not the one that was requested") {
		val signed = signBaselineT()

		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signed,
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_T,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()

		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_T
		embeddedRevocationCount(extended.outputBytes) shouldBe 0
		extended.revocationDataMissing.shouldBeFalse()
	}
})
