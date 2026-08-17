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
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
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
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.*

/**
 * End-to-end tests of the case the whole preservation review turns on: a PAdES B-T document whose
 * **signing certificate has already expired**.
 *
 * Built on a signer certificate minted at test time off the committed CA (see [TestPki]) with its
 * validity ending a month ago, and a TSA that dates its tokens two years back, so the document was
 * timestamped while that certificate was still valid — the shape a real archive would have. Every
 * assertion here is about real bytes produced by the real repositories; nothing is mocked but the
 * token service and the trust/revocation wiring the committed fixtures cannot carry.
 *
 * Three things are pinned:
 * 1. Extending such a document to B-LT produces revocation data that cannot be used, and says so.
 * 2. It *can* be promoted properly when the issuer's CRL explicitly claims to cover expired
 *    certificates, so the app is not writing off documents the standards still allow.
 * 3. Renewal reports a B-T document past that deadline as terminal, not as work to retry.
 *
 * The first case is worth reading carefully, because it is not the shape one might expect. DSS
 * *fetches and embeds* the fresh CRL — it is a valid CRL from the right issuer — and its
 * baseline-LT check counts it, so the document is reported as B-LT by DSS's own level determination
 * and by any validator reading the level structurally. What it cannot do is *use* it:
 * `RevocationDataVerifier` discards a response whose `thisUpdate` postdates the certificate's expiry,
 * so at validation time the signature has no acceptable revocation data.
 *
 * That is why the level alone is not a sufficient signal and
 * [cz.pizavo.omnisign.domain.model.result.ArchivingResult.revocationDataMissing] exists: only the
 * warning-derived flag tells this document apart from the one in the second test, which is genuinely
 * B-LT. The reviewer's original example — a DSS dictionary holding certificates and no revocation
 * data at all — is the *other* shape, the one that arises when the CRL cannot be fetched; there the
 * level does drop to B-T, as `SigningPipelineE2ETest` shows.
 */
class ExpiredCertificatePreservationE2ETest : FunSpec({

	val tmp = tempdir()
	val pass = "test1234"
	val now = System.currentTimeMillis()
	val day = 86_400_000L

	fun fixture(name: String): File {
		val bytes = ExpiredCertificatePreservationE2ETest::class.java.getResourceAsStream("/e2e/$name")?.readBytes()
			?: error("Missing test fixture /e2e/$name")
		return File(tmp, name).also { it.writeBytes(bytes) }
	}

	val caKeyStore = KeyStore.getInstance("PKCS12").apply {
		fixture("ca.test.p12").inputStream().use { load(it, pass.toCharArray()) }
	}
	val caAlias = caKeyStore.aliases().nextElement()
	val caKey = caKeyStore.getKey(caAlias, pass.toCharArray()) as PrivateKey
	val caCert = caKeyStore.getCertificate(caAlias) as X509Certificate

	val pki = TestPki(caCert, caKey)

	val signerLeaf = pki.issueLeaf(
		commonName = "Expired Signer",
		notBefore = Date(now - 3 * 365 * day),
		notAfter = Date(now - 30 * day),
	)
	val signerP12 = File(tmp, "expired-signer.p12").also { it.writeBytes(signerLeaf.p12) }
	val signerCert = signerLeaf.certificate

	val tsaLeaf = pki.issueLeaf(
		commonName = "Backdated TSA",
		notBefore = Date(now - 4 * 365 * day),
		notAfter = Date(now + 365 * day),
		timeStamping = true,
	)
	val tsa = LocalTestTsa.start(tsaLeaf.p12, pass, genTime = { Date(now - 2 * 365 * day) })
	afterSpec { tsa.close() }

	/**
	 * The CRL the verifiers hand to DSS. A `var` because the point of these tests is to compare what
	 * the same document does under a CRL that says nothing about expired certificates and one that
	 * does.
	 */
	var crl: X509CRL = pki.buildCrl(thisUpdate = Date(now - day), nextUpdate = Date(now + 3650 * day))

	fun verifier(alert: StatusAlert?): CommonCertificateVerifier =
		CommonCertificateVerifier().apply {
			setTrustedCertSources(
				CommonTrustedCertificateSource().apply { addCertificate(DSSUtils.loadCertificate(caCert.encoded)) },
			)
			crlSource = ExternalResourcesCRLSource(InMemoryDocument(crl.encoded))
			isCheckRevocationForUntrustedChains = false
			alertOnUncoveredPOE = null
			alertOnInvalidTimestamp = null
			alertOnRevokedCertificate = null
			alertOnExpiredCertificate = null
			alertOnNotYetValidCertificate = null
			alertOnMissingRevocationData = alert
			alertOnNoRevocationAfterBestSignatureTime = alert
		}

	fun embeddedRevocationCount(pdf: ByteArray): Int =
		PDFDocumentValidator(InMemoryDocument(pdf))
			.apply { setCertificateVerifier(CommonCertificateVerifier()) }
			.dssDictionaries
			.sumOf { it.crLs.size + it.ocsPs.size }

	val fileToken = TokenInfo(
		id = "expired-file", name = signerP12.name, type = TokenType.FILE,
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
		CertificateVerifierResult(verifier(alert = null))
	}
	every { dssServiceFactory.buildExtendCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(verifier(arg<() -> StatusAlert>(2)()))
	}
	every { dssServiceFactory.buildLevelInspectionVerifier(any(), any()) } answers {
		CertificateVerifierResult(verifier(alert = null))
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

	/**
	 * A resolved config that permits working with the expired certificate, as
	 * `validation.allowExpiredCertificate` does in production.
	 */
	fun expiredFriendlyConfig(): ResolvedConfig =
		ResolvedConfig.resolve(
			global = GlobalConfig(
				defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T,
				timestampServer = TimestampServerConfig(url = tsa.url),
				validation = ValidationConfig(allowExpiredCertificate = true),
			),
			profile = null,
			operationOverrides = null,
		).getOrNull()!!

	/** A B-T document signed by the expired certificate, timestamped while it was still valid. */
	suspend fun signBaselineT(): ByteArray =
		signingRepository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = certEntry.alias,
				signatureLevel = SignatureLevel.PADES_BASELINE_T,
				addTimestamp = true,
				resolvedConfig = expiredFriendlyConfig(),
			)
		).shouldBeRight().outputBytes

	suspend fun extendTo(level: SignatureLevel, bytes: ByteArray) =
		archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = bytes,
				inputName = "input.pdf",
				targetLevel = level,
				resolvedConfig = expiredFriendlyConfig(),
			)
		)

	test("extending an expired-certificate B-T document to B-LT embeds a CRL it cannot use, and says so") {
		crl = pki.buildCrl(thisUpdate = Date(now - day), nextUpdate = Date(now + 3650 * day))

		val extended = extendTo(SignatureLevel.PADES_BASELINE_LT, signBaselineT()).shouldBeRight()

		extended.revocationDataMissing.shouldBeTrue()
		extended.warnings.any { it.contains("issued after the certificate expired") }.shouldBeTrue()

		embeddedRevocationCount(extended.outputBytes) shouldBe 1
		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("the same document reaches B-LT when the CRL claims to cover expired certificates") {
		crl = pki.buildCrl(
			thisUpdate = Date(now - day),
			nextUpdate = Date(now + 3650 * day),
			expiredCertsOnCRL = Date(now - 3 * 365 * day),
		)

		val extended = extendTo(SignatureLevel.PADES_BASELINE_LT, signBaselineT()).shouldBeRight()

		extended.revocationDataMissing.shouldBeFalse()
		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_LT
		embeddedRevocationCount(extended.outputBytes) shouldBeGreaterThan 0
	}

	test("a B-LT document whose embedded revocation data is unusable is not sealed as if it were sound") {
		crl = pki.buildCrl(thisUpdate = Date(now - day), nextUpdate = Date(now + 3650 * day))
		val extended = extendTo(SignatureLevel.PADES_BASELINE_LT, signBaselineT()).shouldBeRight()
		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_LT
		val file = File(tmp, "unusable-lt.pdf").also { it.writeBytes(extended.outputBytes) }

		val assessment = archivingRepository.needsArchivalRenewal(file.absolutePath).shouldBeRight()

		assessment.need shouldBe RenewalNeed.NEEDED
		assessment.reason shouldBe RenewalReason.LT_REFRESH_NEEDED
		assessment.reason shouldNotBe RenewalReason.LT_NOT_SEALED

		val info = archivingRepository.getDocumentTimestampInfo(extended.outputBytes).shouldBeRight()
		info.level shouldBe SignatureLevel.PADES_BASELINE_LT
		info.containsLtData.shouldBeTrue()
		info.ltMaterialUsable.shouldBeFalse()
	}

	test("a B-LT document whose CRL covers expired certificates is treated as sound and sealed") {
		crl = pki.buildCrl(
			thisUpdate = Date(now - day),
			nextUpdate = Date(now + 3650 * day),
			expiredCertsOnCRL = Date(now - 3 * 365 * day),
		)
		val extended = extendTo(SignatureLevel.PADES_BASELINE_LT, signBaselineT()).shouldBeRight()
		val file = File(tmp, "sound-lt.pdf").also { it.writeBytes(extended.outputBytes) }

		val assessment = archivingRepository.needsArchivalRenewal(file.absolutePath).shouldBeRight()

		assessment.need shouldBe RenewalNeed.NEEDED
		assessment.reason shouldBe RenewalReason.LT_NOT_SEALED

		archivingRepository.getDocumentTimestampInfo(extended.outputBytes).shouldBeRight()
			.ltMaterialUsable.shouldBeTrue()
	}

	test("renewal reports an expired-certificate B-T document as terminal, not as work to retry") {
		crl = pki.buildCrl(thisUpdate = Date(now - day), nextUpdate = Date(now + 3650 * day))
		val file = File(tmp, "terminal-b-t.pdf").also { it.writeBytes(signBaselineT()) }

		val assessment = archivingRepository.needsArchivalRenewal(file.absolutePath).shouldBeRight()

		assessment.need shouldBe RenewalNeed.UNRECOVERABLE
		assessment.reason shouldBe RenewalReason.BELOW_LT
		assessment.dueAt shouldBe signerCert.notAfter.toKotlinInstant()
	}
})
