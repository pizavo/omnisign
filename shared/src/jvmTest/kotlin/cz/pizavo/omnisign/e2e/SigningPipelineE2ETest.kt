package cz.pizavo.omnisign.e2e

import arrow.core.right
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.sun.net.httpserver.HttpServer
import cz.pizavo.omnisign.data.repository.*
import cz.pizavo.omnisign.data.service.FileRenewalCheckCache
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.service.pkcs11CertAlias
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.error.TimestampFailureKind
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.model.result.RenewalReason
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.enumerations.Indication
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.DSSUtils
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
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
 *
 * One more TSA is minted off the committed CA at test time (see [TestPki]): one whose certificate
 * names no revocation address, whose timestamps DSS refuses as proofs of existence while it gathers
 * validation data however sound the rest of the chain is — and whose documents it still validates.
 *
 * It also checks the log capture both repositories open around their DSS calls: a warning DSS logs
 * while it signs a PDF straight to B-LT reaches the signing result, and a signing or an extension that
 * fails at the timestamp server leaves the `eu.europa.esig` logger as it found it.
 *
 * An extension whose timestamp server answers with an HTTP error status is reported as a timestamp
 * failure rather than a revocation one, and an extension given no configuration blames the timestamp
 * server of the active profile. A failed fetch of any other address, a CRL under the timestamp
 * server's own address included, stays a revocation failure.
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

	/** A TSA whose certificate names no CRL distribution point, so DSS counts none of its timestamps as proof. */
	val addresslessTsa = TestPki(caCert, caKey).issueLeaf(
		commonName = "OmniSign Test TSA Without Revocation Address",
		notBefore = Date(System.currentTimeMillis() - 86_400_000L),
		notAfter = Date(System.currentTimeMillis() + 3650 * 86_400_000L),
		timeStamping = true,
		crlDistributionPoint = null,
	).let { LocalTestTsa.start(it.p12, pass) }
	afterSpec { addresslessTsa.close() }

	// A CA-signed CRL with no revoked entries, generated fresh (so it never fails a freshness check),
	// supplied to DSS as an offline source so B-LT/B-LTA extension can embed revocation data without
	// any network: the distribution point the test certificates name lies under the reserved
	// `.invalid` domain, so DSS could not fetch it otherwise.
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
	every { dssServiceFactory.buildTspSource(any()) } answers {
		OnlineTSPSource(firstArg<TimestampServerConfig>().url).apply { setDataLoader(TimestampDataLoader()) }
	}

	val signingRepository = DssSigningRepository(
		tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), Pkcs11SessionCache(), SignatureSpaceErrorDetector(), DssLogCapture(),
	)
	val archivingRepository = DssArchivingRepository(
		configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
		RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
		mockk<RenewalCheckCache>(relaxed = true), SignatureSpaceErrorDetector(), DssLogCapture(),
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

	suspend fun signBaselineT(tsaUrl: String = tsa.url): ByteArray =
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
						timestampServer = TimestampServerConfig(url = tsaUrl),
					),
				),
			)
		).shouldBeRight().outputBytes

	/** An address no TSA listens on, so a timestamp request sent there fails. */
	val unreachableTsaUrl = "http://127.0.0.1:1/tsa"

	/**
	 * Run [operation] with the `eu.europa.esig` logger at ERROR and additive, the state a log capture session
	 * changes while it is open, and check that the operation leaves the logger so: at ERROR, additive, and without
	 * the capture's appender. However the check ends, the logger gets its own level and additivity back.
	 */
	suspend fun leavesDssLoggerAsFound(operation: suspend () -> Unit) {
		val dss = LoggerFactory.getLogger("eu.europa.esig") as Logger
		val ownLevel = dss.level
		val ownAdditivity = dss.isAdditive
		dss.level = Level.ERROR
		dss.isAdditive = true
		try {
			operation()
			dss.level shouldBe Level.ERROR
			dss.isAdditive.shouldBeTrue()
			dss.getAppender(DssLogCapture.APPENDER_NAME).shouldBeNull()
		} finally {
			dss.getAppender(DssLogCapture.APPENDER_NAME)?.let { dss.detachAppender(it) }
			dss.isAdditive = ownAdditivity
			dss.level = ownLevel
		}
	}

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

	test("a TSA that names no revocation address is reported, and the document still validates as B-LT") {
		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signBaselineT(addresslessTsa.url),
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LT,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()

		extended.annotatedWarnings.map { (it.summary as? LocalizableText.Keyed)?.key } shouldContain
			MessageKey.WARNING_REVOCATION_ACCESS_POINT_MISSING
		extended.revocationDataMissing.shouldBeFalse()
		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_LT

		val report = PDFDocumentValidator(InMemoryDocument(extended.outputBytes))
			.apply { setCertificateVerifier(trustAndRevocationVerifier()) }
			.validateDocument().simpleReport
		report.getIndication(report.firstSignatureId) shouldBe Indication.TOTAL_PASSED
	}

	test("a TSA that names no revocation address is reported when a PDF is signed straight to B-LT") {
		val signed = signingRepository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = certEntry.alias,
				signatureLevel = SignatureLevel.PADES_BASELINE_LT,
				addTimestamp = true,
				resolvedConfig = resolvedConfig(
					GlobalConfig(
						defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LT,
						timestampServer = TimestampServerConfig(url = addresslessTsa.url),
					),
				),
			)
		).shouldBeRight()

		signed.annotatedWarnings.map { (it.summary as? LocalizableText.Keyed)?.key } shouldContain
			MessageKey.WARNING_REVOCATION_ACCESS_POINT_MISSING
	}

	test("a signing that fails at the timestamp server leaves the DSS logger as it found it") {
		leavesDssLoggerAsFound {
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
							timestampServer = TimestampServerConfig(url = unreachableTsaUrl),
						),
					),
				)
			).shouldBeLeft().details.shouldNotBeNull() shouldContain unreachableTsaUrl
		}
	}

	test("an extension that fails at the timestamp server leaves the DSS logger as it found it") {
		val signed = signBaselineT()

		leavesDssLoggerAsFound {
			archivingRepository.extendDocument(
				ArchivingParameters(
					inputBytes = signed,
					inputName = "input.pdf",
					targetLevel = SignatureLevel.PADES_BASELINE_LTA,
					resolvedConfig = resolvedConfig(
						GlobalConfig(timestampServer = TimestampServerConfig(url = unreachableTsaUrl)),
					),
				)
			).shouldBeLeft().details.shouldNotBeNull() shouldContain unreachableTsaUrl
		}
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

	/** An [archivingRepository] that keeps its verdicts in [cache] and reads its configuration from [config]. */
	fun cachingRepository(cache: RenewalCheckCache, config: ConfigRepository = configRepository) =
		DssArchivingRepository(
			config, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
			RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
			cache, SignatureSpaceErrorDetector(), DssLogCapture(),
		)

	/** A freshly sealed B-LTA document, written to [name] under the spec's directory. */
	suspend fun sealedFile(name: String): File {
		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signBaselineT(),
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LTA,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url))),
			)
		).shouldBeRight()
		return File(tmp, name).also { it.writeBytes(extended.outputBytes) }
	}

	test("a B-LTA verdict is cached with the profile that reached it") {
		val file = sealedFile("b-lta-cached.pdf")
		val cache = FileRenewalCheckCache(tempdir().toPath().resolve("cache.json"))
		val archive = ResolvedConfig.resolve(GlobalConfig(), ProfileConfig(name = "archive"), null).getOrNull()!!

		cachingRepository(cache).needsArchivalRenewal(file.absolutePath, resolvedConfig = archive)
			.shouldBeRight().need shouldBe RenewalNeed.NOT_NEEDED

		val entry = cache.get(file.absolutePath).shouldNotBeNull()
		entry.profile shouldBe "archive"
		entry.profileRecorded.shouldBeTrue()
	}

	test("a B-LTA verdict reached without a resolved configuration is not cached") {
		val file = sealedFile("b-lta-unresolved.pdf")
		val cache = FileRenewalCheckCache(tempdir().toPath().resolve("cache.json"))
		val unresolvable = mockk<ConfigRepository>()
		coEvery { unresolvable.getCurrentConfig() } returns AppConfig(
			global = GlobalConfig(
				defaultHashAlgorithm = HashAlgorithm.SHA256,
				disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
			),
		)

		cachingRepository(cache, unresolvable).needsArchivalRenewal(file.absolutePath)
			.shouldBeRight().need shouldBe RenewalNeed.NOT_NEEDED

		cache.get(file.absolutePath).shouldBeNull()
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

	/**
	 * Run [block] with the URL of a loopback server that answers every request with the HTTP error
	 * [status] and no body, standing in for a timestamp server that refuses the request.
	 */
	suspend fun withRefusingServer(status: Int, block: suspend (String) -> Unit) {
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			exchange.requestBody.readAllBytes()
			exchange.sendResponseHeaders(status, -1)
			exchange.close()
		}
		server.start()
		try {
			block("http://127.0.0.1:${server.address.port}/")
		} finally {
			server.stop(0)
		}
	}

	test("an extension the timestamp server refuses with an HTTP error is reported as a timestamp failure") {
		withRefusingServer(401) { refusingUrl ->
			val error = archivingRepository.extendDocument(
				ArchivingParameters(
					inputBytes = signBaselineT(),
					inputName = "input.pdf",
					targetLevel = SignatureLevel.PADES_BASELINE_LTA,
					resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = refusingUrl))),
				)
			).shouldBeLeft().shouldBeInstanceOf<ArchivingError.TimestampFailed>()
			error.message shouldContain refusingUrl
			error.details.shouldNotBeNull() shouldContain "401"
		}
	}

	test("an extension under the active profile blames the timestamp server the profile names") {
		val signed = signBaselineT()
		val profiled = mockk<ConfigRepository>()
		coEvery { profiled.getCurrentConfig() } returns AppConfig(
			global = GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url)),
			profiles = mapOf(
				"archive" to ProfileConfig(name = "archive", timestampServer = TimestampServerConfig(url = unreachableTsaUrl)),
			),
			activeProfile = "archive",
		)

		val error = cachingRepository(mockk<RenewalCheckCache>(relaxed = true), profiled).extendDocument(
			ArchivingParameters(
				inputBytes = signed,
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LTA,
			)
		).shouldBeLeft().shouldBeInstanceOf<ArchivingError.TimestampFailed>()
		error.kind shouldBe TimestampFailureKind.UNREACHABLE
		error.message shouldContain unreachableTsaUrl
	}

	/**
	 * An archiving repository wired like the spec's own, except that every timestamp request fails with
	 * [failure], standing in for a fetch DSS could not complete while it extended the document.
	 */
	fun repositoryFailingWith(failure: DSSExternalResourceException): DssArchivingRepository {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		every { factory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
		every { factory.buildExtendCertificateVerifier(any(), any(), any()) } answers {
			CertificateVerifierResult(extendVerifier(arg<() -> StatusAlert>(2)()))
		}
		every { factory.buildLevelInspectionVerifier(any(), any()) } answers {
			CertificateVerifierResult(trustAndRevocationVerifier())
		}
		every { factory.buildTspSource(any()) } returns mockk<OnlineTSPSource>(relaxed = true) {
			every { getTimeStampResponse(any(), any()) } throws failure
		}
		return DssArchivingRepository(
			configRepository, factory, DssWarningSanitizer(), TspErrorDetector(),
			RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
			mockk<RenewalCheckCache>(relaxed = true), SignatureSpaceErrorDetector(), DssLogCapture(),
		)
	}

	test("an extension that fails fetching a CRL under the timestamp server's address is a revocation failure") {
		val tsaUrl = "http://pki.example.invalid"
		val crlUrl = "$tsaUrl/ca.crl"
		val refused = "Not acceptable HTTP Status (HTTP status code : 401 / reason : Unauthorized)"
		val error = repositoryFailingWith(
			DSSExternalResourceException("Unable to process GET call for url [$crlUrl]. Reason : [$refused]"),
		).extendDocument(
			ArchivingParameters(
				inputBytes = signBaselineT(),
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LTA,
				resolvedConfig = resolvedConfig(GlobalConfig(timestampServer = TimestampServerConfig(url = tsaUrl))),
			)
		).shouldBeLeft().shouldBeInstanceOf<ArchivingError.RevocationInfoError>()
		error.details.shouldNotBeNull() shouldContain crlUrl
	}
})
