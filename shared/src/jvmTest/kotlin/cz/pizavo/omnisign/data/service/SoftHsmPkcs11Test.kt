package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.data.repository.CertificateVerifierResult
import cz.pizavo.omnisign.data.repository.DocumentInputErrorDetector
import cz.pizavo.omnisign.data.repository.DssServiceFactory
import cz.pizavo.omnisign.data.repository.DssSigningRepository
import cz.pizavo.omnisign.data.repository.DssWarningSanitizer
import cz.pizavo.omnisign.data.repository.SignatureSpaceErrorDetector
import cz.pizavo.omnisign.data.repository.TspErrorDetector
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.e2e.TestPki
import cz.pizavo.omnisign.platform.PasswordCallback
import eu.europa.esig.dss.enumerations.TokenExtractionStrategy
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.withClue
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.time.Duration.Companion.days

/**
 * Runs the PKCS#11 paths against real tokens: SoftHSM2 with three tokens, two of which hold the same key and
 * certificate, so the aliases of their certificates differ only in the token id they end with.
 *
 * The mock-based specs fake the module; this one covers what they cannot: the out-of-process probe and the
 * no-login certificate listing against a real module, logins with right and wrong PINs, and a PAdES signature made
 * by a key that never leaves its token. The tests run only where [SoftHsm.locate] finds SoftHSM2, OpenSC and the
 * configuration file the `jvmTest` task sets aside, such as on the Linux CI runner; elsewhere they are reported as
 * skipped. Where the environment variable `OMNISIGN_SOFTHSM2_REQUIRED` is `true`, as on that runner, a missing
 * SoftHSM2 fails the spec instead, so the tests cannot be skipped there unnoticed.
 *
 * The tests share the tokens and one test JVM, whose PKCS#11 module keeps a token logged in once any test has
 * logged in to it, so each token has one role: `omnisign-a` and `omnisign-b` hold the shared certificate, and
 * `omnisign-c` is logged in to only by the test that tries a wrong PIN first.
 */
class SoftHsmPkcs11Test : FunSpec({
	val softHsm = SoftHsm.locate()
	val pins = mapOf("omnisign-a" to "1111", "omnisign-b" to "2222", "omnisign-c" to "3333")

	val caKeyStore = KeyStore.getInstance("PKCS12").apply {
		val stream = SoftHsmPkcs11Test::class.java.getResourceAsStream("/e2e/ca.test.p12")
			?: error("Missing test fixture /e2e/ca.test.p12")
		stream.use { load(it, "test1234".toCharArray()) }
	}
	val caAlias = caKeyStore.aliases().nextElement()
	val now = System.currentTimeMillis()
	val leaf by lazy {
		TestPki(
			caKeyStore.getCertificate(caAlias) as X509Certificate,
			caKeyStore.getKey(caAlias, "test1234".toCharArray()) as PrivateKey,
		).issueLeaf(
			commonName = "OmniSign SoftHSM Signer",
			notBefore = Date(now - 1.days.inWholeMilliseconds),
			notAfter = Date(now + 3650.days.inWholeMilliseconds),
		)
	}

	val passwordCallback = mockk<PasswordCallback> {
		every { requestPassword(any(), any()) } answers {
			pins.entries.firstOrNull { firstArg<String>().endsWith(it.key) }?.value
		}
	}
	val probeCache = Pkcs11ProbeCache()
	val sessionCache = Pkcs11SessionCache()
	val tokenService = DssTokenService(
		passwordCallback,
		pkcs11Discoverer = Pkcs11Discoverer(probeCache = probeCache),
		probeCache = probeCache,
		sessionCache = sessionCache,
	)

	val configRepository = mockk<ConfigRepository> { coEvery { getCurrentConfig() } returns AppConfig() }
	val credentialStore = mockk<CredentialStore>(relaxed = true) { every { getPassword(any(), any()) } returns null }
	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true) {
		every { buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
		every { buildSigningCertificateVerifier(any(), any(), any()) } answers {
			CertificateVerifierResult(CommonCertificateVerifier())
		}
	}
	val signingRepository = DssSigningRepository(
		tokenService, configRepository, credentialStore, dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), sessionCache, SignatureSpaceErrorDetector(),
	)

	lateinit var tokens: Map<String, TokenInfo>

	beforeSpec {
		if (softHsm == null) return@beforeSpec
		softHsm.reset()
		pins.forEach { (label, pin) -> softHsm.initToken(label, pin) }
		softHsm.importKey("omnisign-a", pins.getValue("omnisign-a"), leaf.privateKey, leaf.certificate)
		softHsm.importKey("omnisign-b", pins.getValue("omnisign-b"), leaf.privateKey, leaf.certificate)

		probeCache.probeLibrary(softHsm.module.toString())
		tokens = tokenService.discoverTokens().shouldBeRight()
			.filter { it.name in pins }
			.associateBy { it.name }
	}

	test("SoftHSM2 is found where the environment requires it").config(enabledIf = { System.getenv("OMNISIGN_SOFTHSM2_REQUIRED") == "true" }) {
		withClue(
			"OMNISIGN_SOFTHSM2_REQUIRED is true, but SoftHsm.locate() found nothing: it needs SOFTHSM2_CONF as the jvmTest " +
				"task sets it, libsofthsm2.so at OMNISIGN_SOFTHSM2_MODULE or a known path, and softhsm2-util and pkcs11-tool " +
				"on PATH",
		) {
			softHsm.shouldNotBeNull()
		}
	}

	test("discovery reports each token under the id of its serial number, in a slot of its own").config(enabledIf = { softHsm != null }) {
		tokens.keys shouldContainAll pins.keys
		tokens.values.forEach { token ->
			token.type shouldBe TokenType.PKCS11
			token.id shouldStartWith "pkcs11-"
		}
		tokens.values.map { it.pkcs11SlotId }.toSet() shouldHaveSize pins.size
		tokens.values.map { it.id }.toSet() shouldHaveSize pins.size
	}

	test("each token lists only its own certificate without a login, though both hold the same one").config(enabledIf = { softHsm != null }) {
		for (label in listOf("omnisign-a", "omnisign-b")) {
			val token = tokens.getValue(label)
			val listed = tokenService.listCertificatesNoLogin(token).shouldBeRight()

			listed.map { it.alias } shouldBe listOf(pkcs11CertAlias(leaf.certificate, token))
			listed.map { it.pkcs11SlotId } shouldBe listOf(token.pkcs11SlotId)
		}
		verify(exactly = 0) { passwordCallback.requestPassword(any(), any()) }
	}

	test("a wrong PIN is refused, and the right one opens the token afterwards").config(enabledIf = { softHsm != null }) {
		val token = tokens.getValue("omnisign-c")

		tokenService.loadCertificatesSilent(token, "9999").shouldBeLeft()
		tokenService.loadCertificatesSilent(token, pins.getValue("omnisign-c")).shouldBeRight().shouldBeEmpty()
	}

	test("logging in lists the certificate under the alias the no-login listing gave it").config(enabledIf = { softHsm != null }) {
		val token = tokens.getValue("omnisign-a")

		tokenService.loadCertificates(token, pins.getValue("omnisign-a")).shouldBeRight()
			.map { it.alias } shouldBe listOf(pkcs11CertAlias(leaf.certificate, token))
	}

	test("signing by alias logs in only to the token the alias names, and signs with the key it holds").config(enabledIf = { softHsm != null }) {
		clearMocks(passwordCallback, answers = false)
		val token = tokens.getValue("omnisign-b")
		val input = ByteArrayOutputStream().also { out -> PDDocument().use { it.addPage(PDPage()); it.save(out) } }

		val result = signingRepository.signDocument(
			SigningParameters(
				inputBytes = input.toByteArray(),
				inputName = "input.pdf",
				certificateAlias = pkcs11CertAlias(leaf.certificate, token),
				signatureLevel = SignatureLevel.PADES_BASELINE_B,
				addTimestamp = false,
				resolvedConfig = ResolvedConfig.resolve(
					global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B),
					profile = null,
					operationOverrides = null,
				).shouldBeRight(),
			),
		).shouldBeRight()

		verify(exactly = 1) { passwordCallback.requestPassword(any(), any()) }
		verify { passwordCallback.requestPassword("Enter PIN for omnisign-b", any()) }
		val signature = PDFDocumentValidator(InMemoryDocument(result.outputBytes))
			.apply {
				setCertificateVerifier(CommonCertificateVerifier())
				setTokenExtractionStrategy(TokenExtractionStrategy.EXTRACT_CERTIFICATES_ONLY)
			}
			.validateDocument().diagnosticData.signatures.single()
		signature.isSignatureIntact shouldBe true
		signature.isSignatureValid shouldBe true
		signature.signingCertificate.binaries shouldBe leaf.certificate.encoded
	}
})
