package cz.pizavo.omnisign.e2e

import arrow.core.right
import cz.pizavo.omnisign.data.repository.CertificateVerifierResult
import cz.pizavo.omnisign.data.repository.DocumentInputErrorDetector
import cz.pizavo.omnisign.data.repository.DssArchivingRepository
import cz.pizavo.omnisign.data.repository.DssServiceFactory
import cz.pizavo.omnisign.data.repository.DssSigningRepository
import cz.pizavo.omnisign.data.repository.DssWarningSanitizer
import cz.pizavo.omnisign.data.repository.RevocationErrorDetector
import cz.pizavo.omnisign.data.repository.TspErrorDetector
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
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.service.CertificateEntry
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.SigningToken
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import eu.europa.esig.dss.spi.DSSUtils
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date

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
		alias = "e2e-signer",
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

	val configRepository = mockk<ConfigRepository>()
	coEvery { configRepository.getCurrentConfig() } returns AppConfig(global = GlobalConfig())

	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
	every { dssServiceFactory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
	every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(trustAndRevocationVerifier())
	}
	every { dssServiceFactory.buildTspSource(any()) } returns
		OnlineTSPSource(tsa.url).apply { setDataLoader(TimestampDataLoader()) }

	val signingRepository = DssSigningRepository(
		tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(),
	)
	val archivingRepository = DssArchivingRepository(
		configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
		RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
		mockk<RenewalCheckCache>(relaxed = true),
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
	}
})
