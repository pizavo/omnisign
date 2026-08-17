package cz.pizavo.omnisign.e2e

import arrow.core.right
import com.sun.net.httpserver.HttpServer
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
import cz.pizavo.omnisign.domain.model.result.RenewalReason
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.DSSUtils
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
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

	/** Pins the issuing CA, standing in for the trusted-list entry or trust-store anchor. */
	fun verifier(): CommonCertificateVerifier = CommonCertificateVerifier().apply {
		setTrustedCertSources(
			CommonTrustedCertificateSource().apply { addCertificate(DSSUtils.loadCertificate(interCert.encoded)) },
		)
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
		SignatureSpaceErrorDetector(),
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
})

private const val BC = "BC"
private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
private const val RSA_KEY_SIZE = 2048
private const val HTTP_OK = 200
