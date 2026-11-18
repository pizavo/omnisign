package cz.pizavo.omnisign.e2e

import arrow.core.right
import com.sun.net.httpserver.HttpServer
import cz.pizavo.omnisign.data.repository.*
import cz.pizavo.omnisign.data.service.FileRenewalLock
import cz.pizavo.omnisign.data.service.FileRenewalRunRecordStore
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.service.pkcs11CertAlias
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.result.RenewalReason
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.validation.RevocationOutcome
import cz.pizavo.omnisign.domain.model.validation.revocationOutcome
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import cz.pizavo.omnisign.domain.usecase.CheckArchivalRenewalUseCase
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.timedependent.TimeDependentValues
import eu.europa.esig.dss.model.tsl.CertificateTrustTime
import eu.europa.esig.dss.model.tsl.TLInfo
import eu.europa.esig.dss.model.tsl.TLValidationJobSummary
import eu.europa.esig.dss.model.tsl.TrustProperties
import eu.europa.esig.dss.model.tsl.TrustServiceProvider
import eu.europa.esig.dss.model.tsl.TrustServiceStatusAndInformationExtensions
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.DSSUtils
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.*

/**
 * End-to-end tests of the PAdES level every surface reports, against a PKI whose **trust anchor is
 * an issuing CA** rather than a self-signed root.
 *
 * That distinction is the whole point of this spec, and it is why the rest of the suite could not
 * catch what it pins. DSS decides the baseline-LT requirement by walking each certificate chain
 * until it reaches a certificate that is self-signed *or trusted*, demanding revocation data for
 * everything below. A trust anchor needs none and none is embedded for it, so:
 *
 * - when the anchor is a **self-signed root** — the shape of every other fixture here — the walk
 *   stops at the same certificate whether or not the anchors are known, and a verifier carrying no
 *   trust at all reaches the same verdict as one carrying the real thing;
 * - when the anchor is an **issuing CA** — how a trusted list pins a qualified TSA, and how a
 *   profile-scoped trust store pins a private CA — an anchorless verifier walks straight past it and
 *   demands revocation data that was never supposed to be there, reporting a conformant B-LT
 *   document as B-T.
 *
 * That misreport reached the user as an extension dialog offering to *add* validation material the
 * document already carried, as a "New level: B-T" on a successful B-LT extension, and — the
 * expensive one — as a renewal assessment of [RenewalReason.BELOW_LT] that made
 * [cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase] discard correctly renewed output for not
 * having reached its target level.
 *
 * Every level asserted here is therefore read back out of real signed bytes through the production
 * repositories, under anchors that pin the intermediate and nothing above it. The root exists only
 * to issue the intermediate: it is neither trusted nor shipped in the PKCS#12, exactly as a relying
 * party would meet this chain.
 *
 * When a profile trusts the signer's own certificate, the walk stops at the signer: DSS needs no
 * revocation data for it and relates none to it, and the document still reads as B-LT on the TSA's
 * CRL. Renewal must not take that absence for revocation data that has to be refreshed, which no
 * refresh could ever supply, so such a B-LT document is sealed and such a B-LTA one is renewed when
 * its archive timestamp ages.
 *
 * The same documents show which revocation checks validation accepts. Under the anchor DSS accepts
 * the CRL embedded at signing, and it settles that the signer was not revoked. Without the anchor DSS
 * accepts none, so the same good CRL settles nothing.
 *
 * They also show which trusted certificate the per-reference trust policy judges. DSS lists each
 * chain up to the root, past the issuing CA it trusts, so with the root in the trust store too both
 * are trusted, and only the issuing CA, where DSS ends its validation, decides whether a signature
 * or a timestamp is downgraded for the role its anchor is typed for.
 *
 * And they show what a trusted list's trust does to that judgement. An issuing CA a trusted list
 * trusts keeps its trust for both roles, whatever role the store gives it or the root. A list that
 * sets a trust-anchor validity predicate stops trusting a CA once it withdraws the CA's service,
 * and DSS's trusted-list job records when. DSS then passes the CA over for the root above it when
 * the store trusts that root, so the root's role decides, and a CA the store pins itself is held to
 * the role the store gives it. OmniSign's own lists set no such predicate, so with them no trust
 * ends.
 */
class TrustAnchoredLevelE2ETest : FunSpec({

	Security.addProvider(BouncyCastleProvider())

	val tmp = tempdir()
	val pass = "test1234"
	val day = 86_400_000L
	val now = System.currentTimeMillis()

	val crlServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	val crlBase = "http://127.0.0.1:${crlServer.address.port}"

	fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }.generateKeyPair()

	/**
	 * Issue a certificate, optionally with a CRL distribution point. Certificates below the anchor
	 * need one: DSS declines to accept revocation data from an issuer that publishes no access point,
	 * so a chain without them cannot reach B-LT for reasons that have nothing to do with trust.
	 */
	fun issue(
		subject: String,
		subjectKey: PublicKey,
		issuerName: X500Name,
		issuerKey: PrivateKey,
		ca: Boolean,
		crlUrl: String?,
		timeStamping: Boolean = false,
	): X509Certificate {
		val builder = JcaX509v3CertificateBuilder(
			issuerName,
			BigInteger.valueOf(System.nanoTime()),
			Date(now - 365 * day),
			Date(now + 365 * day),
			X500Name("CN=$subject"),
			subjectKey,
		).apply {
			addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
			crlUrl?.let {
				addExtension(
					Extension.cRLDistributionPoints,
					false,
					CRLDistPoint(
						arrayOf(
							DistributionPoint(
								DistributionPointName(
									GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, it)),
								),
								null,
								null,
							),
						),
					),
				)
			}
			when {
				ca -> addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
				timeStamping -> {
					addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))
					addExtension(
						Extension.keyUsage,
						true,
						KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation),
					)
				}
				else -> addExtension(
					Extension.keyUsage,
					true,
					KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation),
				)
			}
		}
		val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC).build(issuerKey)
		return JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer))
	}

	val rootKeys = keyPair()
	val rootCert = issue(
		"Anchored Root CA", rootKeys.public, X500Name("CN=Anchored Root CA"), rootKeys.private,
		ca = true, crlUrl = null,
	)

	val interKeys = keyPair()
	val interCert = issue(
		"Anchored Issuing CA", interKeys.public, JcaX509CertificateHolder(rootCert).subject, rootKeys.private,
		ca = true, crlUrl = null,
	)

	val signerKeys = keyPair()
	val signerCert = issue(
		"Anchored Signer", signerKeys.public, JcaX509CertificateHolder(interCert).subject, interKeys.private,
		ca = false, crlUrl = "$crlBase/issuing.crl",
	)

	val tsaKeys = keyPair()
	val tsaCert = issue(
		"Anchored TSA", tsaKeys.public, JcaX509CertificateHolder(interCert).subject, interKeys.private,
		ca = false, crlUrl = "$crlBase/issuing.crl", timeStamping = true,
	)

	crlServer.createContext("/issuing.crl") { exchange ->
		val builder = X509v2CRLBuilder(JcaX509CertificateHolder(interCert).subject, Date(now - day))
		builder.setNextUpdate(Date(now + 3650 * day))
		val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC).build(interKeys.private)
		val bytes = JcaX509CRLConverter().setProvider(BC).getCRL(builder.build(signer)).encoded
		exchange.responseHeaders.set("Content-Type", "application/pkix-crl")
		exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
		exchange.responseBody.use { it.write(bytes) }
		exchange.close()
	}
	crlServer.executor = null
	crlServer.start()
	afterSpec { crlServer.stop(0) }

	/** A PKCS#12 carrying the leaf and the pinned issuing CA — the root is deliberately absent. */
	fun p12(cert: X509Certificate, key: PrivateKey, alias: String): ByteArray {
		val keyStore = KeyStore.getInstance("PKCS12").apply {
			load(null, null)
			setKeyEntry(alias, key, pass.toCharArray(), arrayOf(cert, interCert))
		}
		val out = ByteArrayOutputStream()
		keyStore.store(out, pass.toCharArray())
		return out.toByteArray()
	}

	val signerP12 = File(tmp, "anchored-signer.p12")
		.also { it.writeBytes(p12(signerCert, signerKeys.private, "anchored-signer")) }
	val tsa = LocalTestTsa.start(p12(tsaCert, tsaKeys.private, "anchored-tsa"), pass)
	afterSpec { tsa.close() }

	val fileToken = TokenInfo(
		id = "anchored-file", name = signerP12.name, type = TokenType.FILE,
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

	/**
	 * Pins the issuing CA, standing in for the trusted-list entry or trust-store anchor, unless
	 * [anchored] is false, which leaves the verifier with no trust anchor at all. [signerTrusted] pins
	 * the signer's own certificate beside it, as a profile that trusts the signer directly does.
	 */
	fun verifier(anchored: Boolean = true, signerTrusted: Boolean = false): CommonCertificateVerifier =
		CommonCertificateVerifier().apply {
			if (anchored) {
				setTrustedCertSources(
					CommonTrustedCertificateSource().apply {
						addCertificate(DSSUtils.loadCertificate(interCert.encoded))
						if (signerTrusted) addCertificate(DSSUtils.loadCertificate(signerCert.encoded))
					},
				)
			}
			crlSource = OnlineCRLSource().apply { setDataLoader(CommonsDataLoader()) }
			isCheckRevocationForUntrustedChains = false
			alertOnUncoveredPOE = null
			alertOnInvalidTimestamp = null
			alertOnRevokedCertificate = null
			alertOnMissingRevocationData = null
			alertOnNoRevocationAfterBestSignatureTime = null
		}

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
		CertificateVerifierResult(verifier())
	}
	every { dssServiceFactory.buildExtendCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(verifier())
	}
	every { dssServiceFactory.buildLevelInspectionVerifier(any(), any()) } answers {
		CertificateVerifierResult(verifier())
	}
	every { dssServiceFactory.buildTspSource(any()) } returns
		OnlineTSPSource(tsa.url).apply { setDataLoader(TimestampDataLoader()) }

	val signingRepository = DssSigningRepository(
		tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), Pkcs11SessionCache(),
		SignatureSpaceErrorDetector(), DssLogCapture(),
	)
	val archivingRepository = DssArchivingRepository(
		configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
		RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
		mockk<RenewalCheckCache>(relaxed = true), SignatureSpaceErrorDetector(), DssLogCapture(),
	)

	/** A [DssValidationRepository] that validates with [verifier], pinning the issuing CA only when [anchored]. */
	fun validationRepository(anchored: Boolean): DssValidationRepository {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		every { factory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
		every { factory.buildValidationCertificateVerifier(any(), any(), any()) } answers {
			CertificateVerifierResult(verifier(anchored))
		}
		return DssValidationRepository(factory, FileTrustStore(tempdir().toPath()), DssWarningSanitizer())
	}

	val anchoredValidation = validationRepository(anchored = true)
	val anchorlessValidation = validationRepository(anchored = false)

	fun plainPdf(): ByteArray {
		val out = ByteArrayOutputStream()
		org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
			doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
			doc.save(out)
		}
		return out.toByteArray()
	}

	fun config(level: SignatureLevel): ResolvedConfig = ResolvedConfig.resolve(
		global = GlobalConfig(
			defaultSignatureLevel = level,
			timestampServer = TimestampServerConfig(url = tsa.url),
		),
		profile = null,
		operationOverrides = null,
	).getOrNull()!!

	suspend fun signAt(level: SignatureLevel) =
		signingRepository.signDocument(
			SigningParameters(
				inputBytes = plainPdf(),
				inputName = "input.pdf",
				certificateAlias = certEntry.alias,
				signatureLevel = level,
				addTimestamp = true,
				resolvedConfig = config(level),
			)
		).shouldBeRight()

	/** Validate [pdf] with [repository] and return the document's one signature. */
	suspend fun validatedSignature(repository: DssValidationRepository, pdf: ByteArray) =
		repository.validateDocument(
			ValidationParameters(
				inputBytes = pdf,
				inputName = "signed.pdf",
				resolvedConfig = config(SignatureLevel.PADES_BASELINE_LT),
			)
		).shouldBeRight().signatures.single()

	test("signing at B-LT reports the level it reached, not the level it was asked for") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LT)

		signed.signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT.name
		embeddedRevocationCount(signed.outputBytes) shouldBeGreaterThan 0
	}

	test("the extension dialog sees a one-step B-LT signature as B-LT") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LT)

		val info = archivingRepository.getDocumentTimestampInfo(signed.outputBytes).shouldBeRight()

		info.level shouldBe SignatureLevel.PADES_BASELINE_LT
		info.containsLtData.shouldBeTrue()
		info.ltMaterialUsable.shouldBeTrue()
	}

	test("extending a B-T document to B-LT reports B-LT as the level achieved") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_T)

		val extended = archivingRepository.extendDocument(
			ArchivingParameters(
				inputBytes = signed.outputBytes,
				inputName = "input.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LT,
				resolvedConfig = config(SignatureLevel.PADES_BASELINE_LT),
			)
		).shouldBeRight()

		extended.achievedLevel shouldBe SignatureLevel.PADES_BASELINE_LT
		extended.newSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT.name
	}

	test("renewal does not mistake a B-LT document for one that never reached B-LT") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LT)
		val file = File(tmp, "anchored-lt.pdf").also { it.writeBytes(signed.outputBytes) }

		val assessment = archivingRepository.needsArchivalRenewal(file.absolutePath).shouldBeRight()

		assessment.reason shouldNotBe RenewalReason.BELOW_LT
	}

	/**
	 * A repository whose level inspection pins the issuing CA for the `corp` profile alone, standing
	 * in for a trust store that holds the anchor in that profile's scope. The active configuration
	 * is the global one, which pins nothing.
	 */
	val corpScopedArchiving = run {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		every { factory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
		every { factory.buildLevelInspectionVerifier(any(), any()) } answers {
			CertificateVerifierResult(verifier(anchored = firstArg<ResolvedConfig?>()?.profileName == "corp"))
		}
		DssArchivingRepository(
			configRepository, factory, DssWarningSanitizer(), TspErrorDetector(),
			RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
			mockk<RenewalCheckCache>(relaxed = true), SignatureSpaceErrorDetector(), DssLogCapture(),
		)
	}

	val corp = ResolvedConfig.resolve(GlobalConfig(), ProfileConfig(name = "corp"), null).getOrNull()!!

	test("the extension dialog judges the level under the profile it extends with, not the active one") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LT)

		corpScopedArchiving.getDocumentTimestampInfo(signed.outputBytes, corp).shouldBeRight()
			.level shouldBe SignatureLevel.PADES_BASELINE_LT
		corpScopedArchiving.getDocumentTimestampInfo(signed.outputBytes).shouldBeRight()
			.level shouldBe SignatureLevel.PADES_BASELINE_T
	}

	test("renewal judges the level under the job's profile, not the active one") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LT)
		val file = File(tmp, "corp-lt.pdf").also { it.writeBytes(signed.outputBytes) }

		corpScopedArchiving.needsArchivalRenewal(file.absolutePath, resolvedConfig = corp).shouldBeRight()
			.reason shouldNotBe RenewalReason.BELOW_LT
		corpScopedArchiving.needsArchivalRenewal(file.absolutePath).shouldBeRight()
			.reason shouldBe RenewalReason.BELOW_LT
	}

	/**
	 * A repository whose level inspection and extension pin the signer's own certificate beside the
	 * issuing CA, standing in for a profile that trusts the signer directly. DSS then needs no
	 * revocation data for the signer and relates none to it, although the CRL embedded for the TSA
	 * comes from the same issuer, while the TSA's chain still needs that CRL to reach B-LT.
	 */
	val signerTrustingArchiving = run {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		every { factory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
		every { factory.buildLevelInspectionVerifier(any(), any()) } answers {
			CertificateVerifierResult(verifier(signerTrusted = true))
		}
		every { factory.buildExtendCertificateVerifier(any(), any(), any()) } answers {
			CertificateVerifierResult(verifier(signerTrusted = true))
		}
		every { factory.buildTspSource(any()) } returns
			OnlineTSPSource(tsa.url).apply { setDataLoader(TimestampDataLoader()) }
		DssArchivingRepository(
			configRepository, factory, DssWarningSanitizer(), TspErrorDetector(),
			RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
			mockk<RenewalCheckCache>(relaxed = true), SignatureSpaceErrorDetector(), DssLogCapture(),
		)
	}

	test("the extension dialog finds the B-LT material of a signer trusted directly usable, though none of it is the signer's") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LT)

		val info = signerTrustingArchiving.getDocumentTimestampInfo(signed.outputBytes).shouldBeRight()

		info.level shouldBe SignatureLevel.PADES_BASELINE_LT
		info.ltMaterialUsable.shouldBeTrue()
	}

	test("renewal seals the B-LT document of a signer trusted directly instead of refreshing revocation data it needs none of") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LT)
		val file = File(tmp, "signer-trusted-lt.pdf").also { it.writeBytes(signed.outputBytes) }

		signerTrustingArchiving.needsArchivalRenewal(file.absolutePath).shouldBeRight()
			.reason shouldBe RenewalReason.LT_NOT_SEALED
	}

	test("a renewal run seals the B-LT document of a signer trusted directly and keeps the sealed document") {
		val dir = File(tmp, "signer-trusted-batch").also { it.mkdirs() }
		val file = File(dir, "signer-trusted-lt.pdf").also { it.writeBytes(signAt(SignatureLevel.PADES_BASELINE_LT).outputBytes) }
		val job = RenewalJob(
			name = "signer-trusted",
			globs = listOf(dir.absolutePath.replace('\\', '/') + "/*.pdf"),
			backupRetention = 0,
		)
		val batchConfig = mockk<ConfigRepository>()
		coEvery { batchConfig.loadConfig() } coAnswers { batchConfig.getCurrentConfig().right() }
		coEvery { batchConfig.getCurrentConfig() } returns AppConfig(
			global = GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url)),
			renewalJobs = mapOf(job.name to job),
		)
		val state = tempdir()
		val batch = RenewBatchUseCase(
			CheckArchivalRenewalUseCase(signerTrustingArchiving),
			ExtendDocumentUseCase(signerTrustingArchiving),
			batchConfig,
			FileRenewalLock(File(state, "renewal.lock").toPath()),
			FileRenewalRunRecordStore(File(state, "renewal-run.json").toPath()),
		)

		val result = batch().shouldNotBeNull()

		result.renewed shouldBe 1
		result.errors shouldBe 0
		result.jobs.single().files.single().reason shouldBe RenewalReason.LT_NOT_SEALED
		signerTrustingArchiving.getDocumentTimestampInfo(file.readBytes()).shouldBeRight()
			.level shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("renewal judges the B-LTA document of a signer trusted directly by its archive timestamp") {
		val signed = signAt(SignatureLevel.PADES_BASELINE_LTA)
		val file = File(tmp, "signer-trusted-lta.pdf").also { it.writeBytes(signed.outputBytes) }

		signerTrustingArchiving.needsArchivalRenewal(file.absolutePath, renewalBufferDays = 400).shouldBeRight()
			.reason shouldBe RenewalReason.TIMESTAMP_EXPIRING
	}

	test("validation accepts the embedded CRL under the anchor, so it settles that the signer was not revoked") {
		val signature = validatedSignature(anchoredValidation, signAt(SignatureLevel.PADES_BASELINE_LT).outputBytes)

		signature.revocations.shouldNotBeEmpty()
		signature.revocations.map { it.accepted }.toSet() shouldBe setOf(true)
		signature.revocations.revocationOutcome(signature.signatureTime) shouldBe RevocationOutcome.NotRevoked
	}

	test("validation without an anchor accepts no CRL, so a good one leaves the signer's status undetermined") {
		val signature = validatedSignature(anchorlessValidation, signAt(SignatureLevel.PADES_BASELINE_LT).outputBytes)

		signature.revocations.shouldNotBeEmpty()
		signature.revocations.map { it.status }.toSet() shouldBe setOf("GOOD")
		signature.revocations.map { it.accepted }.toSet() shouldBe setOf(false)
		signature.revocations.revocationOutcome(signature.signatureTime) shouldBe RevocationOutcome.Undetermined
	}

	/** A trust store holding each of [anchors]' certificates in the global scope, typed for its role. */
	suspend fun storeWith(vararg anchors: Pair<X509Certificate, TrustedCertificateType>): FileTrustStore =
		FileTrustStore(tempdir().toPath()).also { store ->
			anchors.forEach { (cert, type) -> store.add(TrustScope.Global, cert.encoded, type).shouldBeRight() }
		}

	/**
	 * A [DssValidationRepository] whose verifier trusts the anchors [store] resolves, built as the
	 * production factory builds them, together with [unmanaged], certificates a source the store does
	 * not manage trusts, and the trusted list [listed], when given.
	 */
	fun storeValidationRepository(
		store: FileTrustStore,
		vararg unmanaged: X509Certificate,
		listed: TrustedListsCertificateSource? = null,
	): DssValidationRepository {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		every { factory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
		every { factory.buildValidationCertificateVerifier(any(), any(), any()) } answers {
			val sources = listOfNotNull(
				DssServiceFactory.buildTrustedCertSource(secondArg<List<ResolvedTrustAnchor>>()),
				CommonTrustedCertificateSource().apply {
					unmanaged.forEach { addCertificate(DSSUtils.loadCertificate(it.encoded)) }
				},
				listed,
			)
			CertificateVerifierResult(verifier(anchored = false).apply { setTrustedCertSources(*sources.toTypedArray()) })
		}
		return DssValidationRepository(factory, store, DssWarningSanitizer())
	}

	/** A status of a qualified CA service, [status] from [from] until [until], or for good when `null`. */
	fun serviceStatus(status: String, from: Date, until: Date?): TrustServiceStatusAndInformationExtensions =
		TrustServiceStatusAndInformationExtensions.TrustServiceStatusAndInformationExtensionsBuilder()
			.setType(QUALIFIED_CA_SERVICE)
			.setStatus(status)
			.setStartDate(from)
			.setEndDate(until)
			.build()

	/**
	 * A trusted list as DSS's trusted-list job leaves it for a list whose trust-anchor validity
	 * predicate accepts granted services, naming [cert] as a qualified CA service granted a year ago.
	 * When [withdrawnSince] is given, the list withdrew the service then and trusts [cert] only until
	 * that time; otherwise it trusts [cert] for good.
	 */
	fun trustedList(cert: X509Certificate, withdrawnSince: Date? = null): TrustedListsCertificateSource {
		val token = DSSUtils.loadCertificate(cert.encoded)
		val grantedSince = Date(now - 365 * day)
		val list = TLInfo(null, null, null, TRUSTED_LIST_URL)
		val history = listOfNotNull(
			withdrawnSince?.let { serviceStatus(WITHDRAWN_STATUS, it, null) },
			serviceStatus(GRANTED_STATUS, grantedSince, withdrawnSince),
		)
		return TrustedListsCertificateSource().apply {
			setTrustPropertiesByCertificates(
				mapOf(token to listOf(TrustProperties(list, TrustServiceProvider(), TimeDependentValues(history)))),
			)
			setTrustTimeByCertificates(mapOf(token to listOf(CertificateTrustTime(grantedSince, withdrawnSince))))
			summary = TLValidationJobSummary(emptyList(), listOf(list))
		}
	}

	test("a signature anchored at a CA the store does not manage keeps its trust, whatever role the store gives the root") {
		val signature = validatedSignature(
			storeValidationRepository(storeWith(rootCert to TrustedCertificateType.TSA), interCert),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		)

		signature.policyUntrusted.shouldBeFalse()
	}

	test("a timestamp anchored at a CA the store does not manage keeps its trust, whatever role the store gives the root") {
		val timestamp = validatedSignature(
			storeValidationRepository(storeWith(rootCert to TrustedCertificateType.CA), interCert),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		).timestamps.single()

		timestamp.policyUntrusted.shouldBeFalse()
	}

	test("a signature anchored at a store CA trusted for timestamps only is downgraded, though the store trusts the root for signatures") {
		val signature = validatedSignature(
			storeValidationRepository(storeWith(interCert to TrustedCertificateType.TSA, rootCert to TrustedCertificateType.CA)),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		)

		signature.policyUntrusted.shouldBeTrue()
	}

	test("a timestamp anchored at a store CA trusted for signatures only is downgraded, though the store trusts the root for timestamps") {
		val timestamp = validatedSignature(
			storeValidationRepository(storeWith(interCert to TrustedCertificateType.CA, rootCert to TrustedCertificateType.TSA)),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		).timestamps.single()

		timestamp.policyUntrusted.shouldBeTrue()
	}

	test("a signature anchored at a CA a trusted list trusts keeps its trust, whatever role the store gives the root") {
		val signature = validatedSignature(
			storeValidationRepository(storeWith(rootCert to TrustedCertificateType.TSA), listed = trustedList(interCert)),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		)

		signature.policyUntrusted.shouldBeFalse()
	}

	test("a store CA trusted for timestamps only keeps its trust for signatures while a trusted list trusts it") {
		val signature = validatedSignature(
			storeValidationRepository(storeWith(interCert to TrustedCertificateType.TSA), listed = trustedList(interCert)),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		)

		signature.policyUntrusted.shouldBeFalse()
	}

	test("a store CA trusted for timestamps only is held to that role once the trusted list naming it has withdrawn it") {
		val signature = validatedSignature(
			storeValidationRepository(
				storeWith(interCert to TrustedCertificateType.TSA),
				listed = trustedList(interCert, withdrawnSince = Date(now - day)),
			),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		)

		signature.policyUntrusted.shouldBeTrue()
	}

	test("a signature whose CA the trusted list has withdrawn is judged by the store root DSS anchors at instead") {
		val signature = validatedSignature(
			storeValidationRepository(
				storeWith(rootCert to TrustedCertificateType.TSA),
				listed = trustedList(interCert, withdrawnSince = Date(now - day)),
			),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		)

		signature.policyUntrusted.shouldBeTrue()
	}

	test("a timestamp whose CA the trusted list has withdrawn is judged by the store root DSS anchors at instead") {
		val timestamp = validatedSignature(
			storeValidationRepository(
				storeWith(rootCert to TrustedCertificateType.CA),
				listed = trustedList(interCert, withdrawnSince = Date(now - day)),
			),
			signAt(SignatureLevel.PADES_BASELINE_T).outputBytes,
		).timestamps.single()

		timestamp.policyUntrusted.shouldBeTrue()
	}
})

private const val TRUSTED_LIST_URL = "https://tl.omnisign.test/anchored.xml"
private const val QUALIFIED_CA_SERVICE = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC"
private const val GRANTED_STATUS = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted"
private const val WITHDRAWN_STATUS = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn"
private const val BC = "BC"
private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
private const val RSA_KEY_SIZE = 4096
private const val HTTP_OK = 200
