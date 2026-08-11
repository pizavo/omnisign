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
import cz.pizavo.omnisign.data.service.FileRenewalCheckCache
import cz.pizavo.omnisign.data.service.FileRenewalLock
import cz.pizavo.omnisign.data.service.FileRenewalRunRecordStore
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.service.pkcs11CertAlias
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.model.result.RenewalReason
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.service.CertificateEntry
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.SigningToken
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.CheckArchivalRenewalUseCase
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase
import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.model.InMemoryDocument
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date

/**
 * End-to-end tests of the **renewal batch acting on real files**: the real [RenewBatchUseCase] over
 * a real [DssArchivingRepository], with the real on-disk lock, cache and run-record store, matching
 * documents by glob out of a temporary directory.
 *
 * Everything else in the suite verifies what the app *decides*. This verifies what it *does* — the
 * part where an archive is overwritten, and therefore the part where a mistake is expensive. The
 * decision tests can pass while the write path silently replaces a good document with a worse one,
 * which is exactly the failure the whole preservation rework exists to prevent.
 *
 * The PKI is minted per run by [TestPki] so the certificate dates are test parameters rather than
 * whatever was frozen into a committed fixture.
 */
class RenewalBatchE2ETest : FunSpec({

	val tmp = tempdir()
	val state = tempdir()
	val pass = "test1234"
	val now = System.currentTimeMillis()
	val day = 86_400_000L

	fun fixture(name: String): File {
		val bytes = RenewalBatchE2ETest::class.java.getResourceAsStream("/e2e/$name")?.readBytes()
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

	val validSigner = pki.issueLeaf("Batch Signer", Date(now - 365 * day), Date(now + 2 * 365 * day))
	val expiredSigner = pki.issueLeaf("Batch Expired Signer", Date(now - 3 * 365 * day), Date(now - 30 * day))

	/** Issues tokens dated an hour ago, so freshly fetched revocation data postdates the signature. */
	val tsaLeaf = pki.issueLeaf(
		"Batch TSA", Date(now - 365 * day), Date(now + 2 * 365 * day), timeStamping = true,
	)
	val tsa = LocalTestTsa.start(tsaLeaf.p12, pass, genTime = { Date(System.currentTimeMillis() - 3_600_000L) })

	/** Dates tokens two years back, inside [expiredSigner]'s validity, as a real archive would be. */
	val backdatedTsa = LocalTestTsa.start(tsaLeaf.p12, pass, genTime = { Date(now - 2 * 365 * day) })

	/** A TSA whose own certificate expires inside the renewal buffer, so its seals age out soon. */
	val expiringTsaLeaf = pki.issueLeaf(
		"Batch Expiring TSA", Date(now - 365 * day), Date(now + 30 * day), timeStamping = true,
	)
	val expiringTsa = LocalTestTsa.start(expiringTsaLeaf.p12, pass)

	afterSpec {
		tsa.close()
		backdatedTsa.close()
		expiringTsa.close()
	}

	var crl: X509CRL = pki.buildCrl(thisUpdate = Date(now - 2 * 3_600_000L), nextUpdate = Date(now + 3650 * day))

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

	fun tokenFor(p12: File, certificate: X509Certificate): Pair<TokenInfo, CertificateEntry> {
		val token = TokenInfo(
			id = p12.name, name = p12.name, type = TokenType.FILE, path = p12.absolutePath, requiresPin = false,
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
		return token to entry
	}

	val validP12 = File(tmp, "batch-signer.p12").also { it.writeBytes(validSigner.p12) }
	val expiredP12 = File(tmp, "batch-expired.p12").also { it.writeBytes(expiredSigner.p12) }
	val (validToken, validEntry) = tokenFor(validP12, validSigner.certificate)
	val (expiredToken, expiredEntry) = tokenFor(expiredP12, expiredSigner.certificate)

	fun signingToken(p12: File): SigningToken {
		val token = Pkcs12SignatureToken(p12, KeyStore.PasswordProtection(pass.toCharArray()))
		return object : SigningToken {
			override fun getDssToken(): Any = token
			override fun close() = token.close()
		}
	}

	val tokenService = mockk<TokenService>()
	coEvery { tokenService.discoverTokens() } returns listOf(validToken, expiredToken).right()
	coEvery { tokenService.probeTokenPresent(any()) } returns true
	coEvery { tokenService.loadCertificatesSilent(any(), any()) } returns listOf(validEntry, expiredEntry).right()
	coEvery { tokenService.getSigningToken(any(), any()) } answers {
		signingToken(if (firstArg<TokenInfo>().id == expiredToken.id) expiredP12 else validP12).right()
	}
	coEvery { tokenService.openSigningToken(any(), any()) } answers {
		signingToken(if (firstArg<TokenInfo>().id == expiredToken.id) expiredP12 else validP12).right()
	}

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
	every { dssServiceFactory.buildTspSource(any()) } answers {
		OnlineTSPSource(firstArg<TimestampServerConfig>().url).apply { setDataLoader(TimestampDataLoader()) }
	}

	val signingRepository = DssSigningRepository(
		tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), Pkcs11SessionCache(),
	)
	val archivingRepository = DssArchivingRepository(
		configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
		RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
		FileRenewalCheckCache(File(state, "renewal-cache.cbor").toPath()),
	)

	val batch = RenewBatchUseCase(
		CheckArchivalRenewalUseCase(archivingRepository),
		ExtendDocumentUseCase(archivingRepository),
		configRepository,
		FileRenewalLock(File(state, "renewal.lock").toPath()),
		FileRenewalRunRecordStore(File(state, "renewal-run.json").toPath()),
	)

	fun plainPdf(): ByteArray {
		val out = ByteArrayOutputStream()
		org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
			doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
			doc.save(out)
		}
		return out.toByteArray()
	}

	fun configWith(tsaUrl: String, allowExpired: Boolean = false): GlobalConfig = GlobalConfig(
		defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T,
		timestampServer = TimestampServerConfig(url = tsaUrl),
		validation = ValidationConfig(allowExpiredCertificate = allowExpired),
	)

	fun resolved(global: GlobalConfig): ResolvedConfig =
		ResolvedConfig.resolve(global = global, profile = null, operationOverrides = null).getOrNull()!!

	suspend fun signBaselineT(entry: CertificateEntry, global: GlobalConfig): ByteArray =
		signingRepository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = entry.alias,
				signatureLevel = SignatureLevel.PADES_BASELINE_T,
				addTimestamp = true,
				resolvedConfig = resolved(global),
			)
		).shouldBeRight().outputBytes

	/** Runs one batch over [dir], with the job's TSA pointing at the long-lived local authority. */
	suspend fun runBatchOver(dir: File, jobName: String = "j") = run {
		val job = RenewalJob(
			name = jobName,
			globs = listOf(dir.absolutePath.replace('\\', '/') + "/*.pdf"),
			backupRetention = 3,
		)
		coEvery { configRepository.getCurrentConfig() } returns AppConfig(
			global = configWith(tsa.url, allowExpired = true),
			renewalJobs = mapOf(jobName to job),
		)
		batch()
	}

	fun levelOf(file: File): SignatureLevel? =
		archivingRepository.let { repo ->
			kotlinx.coroutines.runBlocking { repo.getDocumentTimestampInfo(file.readBytes()) }
				.shouldBeRight().level
		}

	fun backupsBeside(file: File): Array<File> =
		file.parentFile.listFiles { f: File -> f.name.startsWith(file.name) && f.name.endsWith(".bak") } ?: emptyArray()

	test("a B-T document in a job is promoted to B-LTA in place, with a backup beside it") {
		val dir = File(tmp, "promote").also { it.mkdirs() }
		val target = File(dir, "b-t.pdf")
		val original = signBaselineT(validEntry, configWith(tsa.url))
		target.writeBytes(original)

		val result = runBatchOver(dir).shouldNotBeNull()

		result.renewed shouldBe 1
		result.errors shouldBe 0
		levelOf(target) shouldBe SignatureLevel.PADES_BASELINE_LTA
		backupsBeside(target) shouldHaveSize 1
		backupsBeside(target).first().readBytes() shouldBe original
		result.jobs.first().files.first().reason shouldBe RenewalReason.BELOW_LT
	}

	test("a stale B-LT document is refreshed and sealed within a single run") {
		val dir = File(tmp, "chain").also { it.mkdirs() }
		val target = File(dir, "stale-lt.pdf")

		crl = pki.buildCrl(thisUpdate = Date(now - 2 * 3_600_000L), nextUpdate = Date(now + 3650 * day))
		val staleLt = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signBaselineT(validEntry, configWith(tsa.url)),
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LT,
				resolvedConfig = resolved(configWith(tsa.url)),
			)
		).shouldBeRight().outputBytes
		target.writeBytes(staleLt)

		archivingRepository.needsArchivalRenewal(target.absolutePath).shouldBeRight()
			.reason shouldBe RenewalReason.LT_REFRESH_NEEDED

		crl = pki.buildCrl(thisUpdate = Date(System.currentTimeMillis()), nextUpdate = Date(now + 3650 * day))
		val result = runBatchOver(dir, jobName = "chain").shouldNotBeNull()

		result.renewed shouldBe 1
		result.errors shouldBe 0
		levelOf(target) shouldBe SignatureLevel.PADES_BASELINE_LTA
		result.jobs.first().files.first().reason shouldBe RenewalReason.LT_NOT_SEALED
	}

	test("a B-T document whose signing certificate expired is left untouched and reported once") {
		val dir = File(tmp, "terminal").also { it.mkdirs() }
		val target = File(dir, "expired.pdf")
		val original = signBaselineT(expiredEntry, configWith(backdatedTsa.url, allowExpired = true))
		target.writeBytes(original)

		val first = runBatchOver(dir, jobName = "terminal").shouldNotBeNull()

		first.unrecoverable shouldBe 1
		first.errors shouldBe 0
		first.renewed shouldBe 0
		first.success shouldBe true
		first.jobs.first().files.first().status shouldBe RenewFileStatus.Status.UNRECOVERABLE
		first.jobs.first().newlyUnrecoverable shouldBe 1
		target.readBytes() shouldBe original
		backupsBeside(target) shouldHaveSize 0

		val second = runBatchOver(dir, jobName = "terminal").shouldNotBeNull()

		second.unrecoverable shouldBe 1
		second.jobs.first().newlyUnrecoverable shouldBe 0
		target.readBytes() shouldBe original
	}

	test("a B-LTA document whose timestamp certificate expires inside the buffer is due for renewal") {
		val dir = File(tmp, "aging").also { it.mkdirs() }
		val target = File(dir, "aging-lta.pdf")

		crl = pki.buildCrl(thisUpdate = Date(System.currentTimeMillis()), nextUpdate = Date(now + 3650 * day))
		val sealed = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signBaselineT(validEntry, configWith(expiringTsa.url)),
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LTA,
				resolvedConfig = resolved(configWith(expiringTsa.url)),
			)
		).shouldBeRight().outputBytes
		target.writeBytes(sealed)

		val assessment = archivingRepository.needsArchivalRenewal(target.absolutePath).shouldBeRight()

		assessment.need shouldBe RenewalNeed.NEEDED
		assessment.reason shouldBe RenewalReason.TIMESTAMP_EXPIRING
		assessment.deadlineIsFinal shouldBe false
	}
})
