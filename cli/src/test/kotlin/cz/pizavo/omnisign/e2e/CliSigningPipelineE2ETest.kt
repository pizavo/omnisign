package cz.pizavo.omnisign.e2e

import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.data.repository.CertificateVerifierResult
import cz.pizavo.omnisign.data.repository.DocumentInputErrorDetector
import cz.pizavo.omnisign.data.repository.DssArchivingRepository
import cz.pizavo.omnisign.data.repository.DssServiceFactory
import cz.pizavo.omnisign.data.repository.DssSigningRepository
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.repository.DssWarningSanitizer
import cz.pizavo.omnisign.data.repository.RevocationErrorDetector
import cz.pizavo.omnisign.data.repository.TspErrorDetector
import cz.pizavo.omnisign.data.service.DssTokenService
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * End-to-end test that drives the real CLI `sign` and `timestamp` commands against the production
 * signing/archiving pipeline, a committed throwaway PKCS#12, and an in-process RFC 3161 TSA — the
 * CLI-level counterpart of the repository-level `SigningPipelineE2ETest` in `:shared`.
 *
 * Signing goes through the new `--keystore` path (a PKCS#12 file is not a discoverable token), so a
 * real [DssTokenService] loads the real key; only the trusted-list and PDF-object-factory hooks on
 * [DssServiceFactory] are pinned to bare, offline instances, and the TSA is a loopback HTTP
 * [LocalTestTsa]. Covers Tier 1 (PAdES-B) and Tier 2 (PAdES-B-T) only — B-LT/B-LTA stay at the
 * repository level, where the offline CRL/trust the test PKI needs can be injected.
 */
class CliSigningPipelineE2ETest : FunSpec({

	val tmp = tempdir()
	val pass = "test1234"

	fun fixture(name: String): File {
		val bytes = CliSigningPipelineE2ETest::class.java.getResourceAsStream("/e2e/$name")?.readBytes()
			?: error("Missing test fixture /e2e/$name")
		return File(tmp, name).also { it.writeBytes(bytes) }
	}

	val signerP12 = fixture("signer.test.p12")
	val tsa = LocalTestTsa.start(fixture("tsa.test.p12").readBytes(), pass)
	afterSpec { tsa.close() }

	val isolatedConfig = AppConfig(
		global = GlobalConfig(
			timestampServer = TimestampServerConfig(url = tsa.url),
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
		),
	)

	val passwordCallback = mockk<PasswordCallback>(relaxed = true)
	val configRepository = mockk<ConfigRepository>()
	coEvery { configRepository.getCurrentConfig() } returns isolatedConfig

	val tokenService: TokenService = DssTokenService(passwordCallback)

	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
	every { dssServiceFactory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
	every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(
			CommonCertificateVerifier().apply {
				alertOnMissingRevocationData = null
				alertOnUncoveredPOE = null
				alertOnInvalidTimestamp = null
				alertOnNoRevocationAfterBestSignatureTime = null
				alertOnRevokedCertificate = null
			},
		)
	}
	every { dssServiceFactory.buildExtendCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(
			CommonCertificateVerifier().apply {
				alertOnMissingRevocationData = null
				alertOnUncoveredPOE = null
				alertOnInvalidTimestamp = null
				alertOnNoRevocationAfterBestSignatureTime = null
				alertOnRevokedCertificate = null
			},
		)
	}
	every { dssServiceFactory.buildTspSource(any()) } returns
		OnlineTSPSource(tsa.url).apply { setDataLoader(TimestampDataLoader()) }

	val signingRepository = DssSigningRepository(
		tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), Pkcs11SessionCache(),
	)
	val archivingRepository = DssArchivingRepository(
		configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
		RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
		mockk<RenewalCheckCache>(relaxed = true),
	)

	extension(
		KoinExtension(
			module {
				single<PasswordCallback> { passwordCallback }
				single<ConfigRepository> { configRepository }
				single { SignDocumentUseCase(signingRepository) }
				single { ExtendDocumentUseCase(archivingRepository) }
			},
			mode = KoinLifecycleMode.Test,
		),
	)

	fun plainPdf(): ByteArray {
		val out = ByteArrayOutputStream()
		org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
			doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
			doc.save(out)
		}
		return out.toByteArray()
	}

	fun diagnosticOf(file: File) =
		PDFDocumentValidator(InMemoryDocument(file.readBytes()))
			.apply { setCertificateVerifier(CommonCertificateVerifier()) }
			.validateDocument().diagnosticData

	fun signWithKeystore(output: File) {
		val input = File(tmp, "input-${output.nameWithoutExtension}.pdf").also { it.writeBytes(plainPdf()) }
		Omnisign().test(
			listOf(
				"sign",
				"--keystore", signerP12.absolutePath,
				"--keystore-password", pass,
				"--no-timestamp",
				"-f", input.absolutePath,
				"-o", output.absolutePath,
			),
		).statusCode shouldBe 0
	}

	test("omnisign sign --keystore produces a verifiable PAdES-B signature") {
		val output = File(tmp, "signed.pdf")

		signWithKeystore(output)

		val signatures = diagnosticOf(output).signatures
		signatures.shouldHaveSize(1)
		signatures.first().isSignatureIntact.shouldBeTrue()
	}

	test("omnisign timestamp extends a keystore-signed PDF to PAdES-B-T against the local TSA") {
		val signed = File(tmp, "to-extend.pdf")
		signWithKeystore(signed)
		val extended = File(tmp, "extended.pdf")

		val result = Omnisign().test(
			listOf("timestamp", "-f", signed.absolutePath, "-o", extended.absolutePath, "-l", "PADES_BASELINE_T"),
		)

		result.statusCode shouldBe 0
		diagnosticOf(extended).timestampList.shouldNotBeEmpty()
	}
})
