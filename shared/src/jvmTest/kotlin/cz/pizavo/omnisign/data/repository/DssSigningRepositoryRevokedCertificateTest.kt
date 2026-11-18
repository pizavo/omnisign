package cz.pizavo.omnisign.data.repository

import arrow.core.right
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
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import cz.pizavo.omnisign.e2e.LocalTestTsa
import cz.pizavo.omnisign.e2e.TestPki
import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.enumerations.SignatureAlgorithm
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.time.Instant

/**
 * Verifies that [DssSigningRepository] refuses to sign with a certificate that its issuer has
 * revoked, before the key signs, and only then: not when revocation checking is off, and not for
 * another signer's revoked certificate, which signing at B-LT reports as a warning.
 *
 * The signers are minted off the committed end-to-end CA by [TestPki], and the CA's CRL is handed
 * to DSS through an offline source by a verifier configured as OmniSign's signing verifier is, so
 * nothing reaches the network.
 */
class DssSigningRepositoryRevokedCertificateTest : FunSpec({

	val pass = "test1234"
	val day = 86_400_000L
	val now = System.currentTimeMillis()
	val tmp = tempdir()
	val trustStoreDir = tempdir()

	fun resource(name: String): ByteArray =
		DssSigningRepositoryRevokedCertificateTest::class.java.getResourceAsStream("/e2e/$name")!!.use { it.readBytes() }

	val caKeyStore = KeyStore.getInstance("PKCS12").apply { load(resource("ca.test.p12").inputStream(), pass.toCharArray()) }
	val caAlias = caKeyStore.aliases().nextElement()
	val caCert = caKeyStore.getCertificate(caAlias) as X509Certificate
	val ca = TestPki(caCert, caKeyStore.getKey(caAlias, pass.toCharArray()) as PrivateKey)

	val tsa = LocalTestTsa.start(resource("tsa.test.p12"), pass)
	afterSpec { tsa.close() }

	val revokedSigner = ca.issueLeaf("OmniSign Test Revoked Signer", Date(now - 3 * day), Date(now + 365 * day))
	val coSigner = ca.issueLeaf("OmniSign Test Co-Signer", Date(now - 3 * day), Date(now + 365 * day))
	val intermediate = ca.issueLeaf(
		"OmniSign Test Intermediate CA", Date(now - 3 * day), Date(now + 365 * day), certificateAuthority = true,
	)
	val intermediateCa = TestPki(intermediate.certificate, intermediate.privateKey)
	val signerUnderIntermediate =
		intermediateCa.issueLeaf("OmniSign Test Signer Under Intermediate", Date(now - 3 * day), Date(now + 365 * day))

	/** A CRL from [issuer], produced an hour ago, that lists [revoked], each revoked a day ago. */
	fun crlRevoking(vararg revoked: X509Certificate, issuer: TestPki = ca): X509CRL = issuer.buildCrl(
		thisUpdate = Date(now - 3_600_000L),
		nextUpdate = Date(now + 30 * day),
		revoked = revoked.associate { it.serialNumber to Date(now - day) },
	)

	/**
	 * A verifier configured as [DssServiceFactory.buildSigningCertificateVerifier] configures one
	 * when revocation checking is on, but fetching revocation data from [crls] only, and trusting
	 * the committed CA unless [trusted] is false.
	 */
	fun signingVerifier(crls: List<X509CRL>, alert: StatusAlert?, trusted: Boolean): CommonCertificateVerifier =
		CommonCertificateVerifier().apply {
			if (trusted) {
				setTrustedCertSources(CommonTrustedCertificateSource().apply { addCertificate(CertificateToken(caCert)) })
			}
			crlSource = ExternalResourcesCRLSource(*crls.map { InMemoryDocument(it.encoded) }.toTypedArray())
			isCheckRevocationForUntrustedChains = false
			alertOnMissingRevocationData = null
			alertOnNoRevocationAfterBestSignatureTime = null
			alertOnUncoveredPOE = alert
			alertOnInvalidTimestamp = alert
			alertOnRevokedCertificate = alert
		}

	/** The DSS tokens the repositories opened, spied on so that a test can tell whether a key signed. */
	val openedTokens = mutableListOf<Pkcs12SignatureToken>()

	/** The PKCS#12 token [leaf] is found on, its file written to a temporary directory. */
	fun tokenOf(leaf: TestPki.Leaf): TokenInfo {
		val file = File(tmp, "${leaf.certificate.serialNumber}.p12").apply { if (!exists()) writeBytes(leaf.p12) }
		return TokenInfo(
			id = "file-${leaf.certificate.serialNumber}", name = file.name, type = TokenType.FILE,
			path = file.absolutePath, requiresPin = false,
		)
	}

	/** The alias a repository built by `repository` for [leaf] lists its certificate under. */
	fun aliasOf(leaf: TestPki.Leaf): String = pkcs11CertAlias(leaf.certificate, tokenOf(leaf))

	/**
	 * A repository whose only token holds [leaf], and whose signing verifier fetches [crls] and
	 * trusts the committed CA unless [trusted] is false.
	 */
	fun repository(leaf: TestPki.Leaf, crls: List<X509CRL>, trusted: Boolean = true): DssSigningRepository {
		val token = tokenOf(leaf)
		val entry = CertificateEntry(
			alias = aliasOf(leaf),
			subjectDN = leaf.certificate.subjectX500Principal.toString(),
			issuerDN = leaf.certificate.issuerX500Principal.toString(),
			serialNumber = leaf.certificate.serialNumber.toString(),
			validFrom = leaf.certificate.notBefore.toKotlinInstant(),
			validTo = leaf.certificate.notAfter.toKotlinInstant(),
			keyUsages = emptyList(),
			tokenInfo = token,
		)
		fun signingToken(): SigningToken {
			val dssToken = spyk(Pkcs12SignatureToken(File(token.path!!), KeyStore.PasswordProtection(pass.toCharArray())))
			openedTokens += dssToken
			return object : SigningToken {
				override fun getDssToken(): Any = dssToken
				override fun close() = dssToken.close()
			}
		}

		val tokenService = mockk<TokenService>()
		coEvery { tokenService.discoverTokens() } returns listOf(token).right()
		coEvery { tokenService.probeTokenPresent(any()) } returns true
		coEvery { tokenService.loadCertificatesSilent(any(), any()) } returns listOf(entry).right()
		coEvery { tokenService.getSigningToken(any(), any()) } answers { signingToken().right() }
		coEvery { tokenService.openSigningToken(any(), any()) } answers { signingToken().right() }

		val configRepository = mockk<ConfigRepository>()
		coEvery { configRepository.getCurrentConfig() } returns AppConfig(global = GlobalConfig())

		val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
		every { dssServiceFactory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
		every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
			CertificateVerifierResult(signingVerifier(crls, arg<() -> StatusAlert>(2)(), trusted))
		}
		every { dssServiceFactory.buildLevelInspectionVerifier(any(), any()) } answers {
			CertificateVerifierResult(signingVerifier(crls, null, trusted))
		}
		every { dssServiceFactory.buildTspSource(any()) } answers {
			OnlineTSPSource(firstArg<TimestampServerConfig>().url).apply { setDataLoader(TimestampDataLoader()) }
		}

		return DssSigningRepository(
			tokenService, configRepository, mockk<CredentialStore>(relaxed = true), dssServiceFactory,
			AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
			FileTrustStore(trustStoreDir.toPath()), DocumentInputErrorDetector(), Pkcs11SessionCache(),
			SignatureSpaceErrorDetector(), DssLogCapture(),
		)
	}

	fun plainPdf(): ByteArray = ByteArrayOutputStream().also { out ->
		PDDocument().use { doc ->
			doc.addPage(PDPage())
			doc.save(out)
		}
	}.toByteArray()

	fun resolved(global: GlobalConfig): ResolvedConfig =
		ResolvedConfig.resolve(global = global, profile = null, operationOverrides = null).getOrNull()!!

	/** Sign [input] with [leaf] at [level], under [global], adding no timestamp that [level] does not. */
	suspend fun DssSigningRepository.sign(
		leaf: TestPki.Leaf,
		level: SignatureLevel,
		input: ByteArray = plainPdf(),
		global: GlobalConfig = GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url)),
	) = signDocument(
		SigningParameters(
			inputBytes = input,
			inputName = "input.pdf",
			certificateAlias = aliasOf(leaf),
			signatureLevel = level,
			addTimestamp = false,
			resolvedConfig = resolved(global),
		)
	)

	beforeTest { openedTokens.clear() }

	listOf(SignatureLevel.PADES_BASELINE_B, SignatureLevel.PADES_BASELINE_LT).forEach { level ->
		test("signing at ${level.name} with a certificate its issuer has revoked is refused before the key signs, closing the token") {
			val error = repository(revokedSigner, listOf(crlRevoking(revokedSigner.certificate)))
				.sign(revokedSigner, level)
				.shouldBeLeft().shouldBeInstanceOf<SigningError.CertificateRevoked>()

			error.message shouldBe "The certificate issued to OmniSign Test Revoked Signer has been revoked or " +
					"suspended, so the document was not signed: the signature would not validate. " +
					"Sign with another certificate"
			error.details shouldBe "Subject: CN=OmniSign Test Revoked Signer; " +
					"serial number: ${revokedSigner.certificate.serialNumber}; " +
					"issuer: ${caCert.subjectX500Principal.name}; " +
					"revoked on: ${Instant.fromEpochMilliseconds((now - day) / 1000 * 1000)}; " +
					"reason: keyCompromise"
			openedTokens.shouldNotBeEmpty()
			openedTokens.forEach { verify(exactly = 0) { it.sign(any(), any<SignatureAlgorithm>(), any()) } }
			openedTokens.forEach { verify { it.close() } }
		}
	}

	test("signing under an intermediate CA that has been revoked is refused, naming the CA") {
		val error = repository(
			signerUnderIntermediate,
			listOf(crlRevoking(intermediate.certificate), crlRevoking(issuer = intermediateCa)),
		)
			.sign(signerUnderIntermediate, SignatureLevel.PADES_BASELINE_B)
			.shouldBeLeft().shouldBeInstanceOf<SigningError.CertificateRevoked>()

		error.message shouldContain "The certificate issued to OmniSign Test Intermediate CA has been revoked"
		error.details!! shouldContain "serial number: ${intermediate.certificate.serialNumber};"
	}

	test("a certificate its issuer has revoked signs when revocation checking is off") {
		repository(revokedSigner, listOf(crlRevoking(revokedSigner.certificate)))
			.sign(
				revokedSigner,
				SignatureLevel.PADES_BASELINE_B,
				global = GlobalConfig(validation = ValidationConfig(checkRevocation = false)),
			)
			.shouldBeRight()
	}

	test("signing at B-B under a chain that leads to no trusted certificate warns of no skipped revocation check") {
		val result = repository(revokedSigner, listOf(crlRevoking(revokedSigner.certificate)), trusted = false)
			.sign(revokedSigner, SignatureLevel.PADES_BASELINE_B)
			.shouldBeRight()

		result.annotatedWarnings.shouldBeEmpty()
		result.rawWarnings.filter { it.contains("revocation check is skipped", ignoreCase = true) }.shouldBeEmpty()
	}

	test("a co-signer's revoked certificate does not stop signing at B-LT, which reports it as a warning") {
		val signedBeforeTheRevocation = repository(revokedSigner, listOf(crlRevoking()))
			.sign(revokedSigner, SignatureLevel.PADES_BASELINE_B)
			.shouldBeRight().outputBytes

		val result = repository(coSigner, listOf(crlRevoking(revokedSigner.certificate)))
			.sign(coSigner, SignatureLevel.PADES_BASELINE_LT, input = signedBeforeTheRevocation)
			.shouldBeRight()

		result.rawWarnings.filter { it.contains("revoked", ignoreCase = true) }.shouldNotBeEmpty()
	}
})
