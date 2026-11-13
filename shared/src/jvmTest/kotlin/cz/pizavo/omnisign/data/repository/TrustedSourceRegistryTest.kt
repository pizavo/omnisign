package cz.pizavo.omnisign.data.repository

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.pizavo.omnisign.data.service.TrustedListCompiler
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.TrustedListAddress
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.SignatureAlgorithm
import eu.europa.esig.dss.enumerations.SignaturePackaging
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.SignatureValue
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.xades.XAdESSignatureParameters
import eu.europa.esig.dss.xades.signature.XAdESService
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant
import eu.europa.esig.dss.enumerations.SignatureLevel as DssSignatureLevel

/**
 * Verifies the network-free invariants of [TrustedSourceRegistry]: the
 * direct-trusted-anchor composition contract, per-call isolation (no trust
 * bleed between configurations sharing one registry), and that the warmup /
 * refresh / shutdown entry points are safe no-ops — recording neither success
 * nor failure on the refresh signal — when no trusted lists are configured. EU
 * LOTL paths are intentionally not exercised here because they require network
 * access.
 *
 * It also verifies that a custom list whose signing certificate cannot be read fails on its own:
 * composition goes on with the other sources and says why, warm-up and both refreshes count the
 * list as failed instead of throwing, and the reason is logged in one line, at WARN only when it
 * is new. No job is built for such a list, so these tests reach neither the network nor the
 * trusted-list cache.
 *
 * Last, it verifies that a custom list configured with a signing certificate supplies trust only
 * while its signature verifies against that certificate, that the warning and the log say so when
 * it does not, and that a list configured without one is used whatever its signature. These lists
 * are compiled by [TrustedListCompiler], signed or left unsigned by the test, read from `file:`
 * URLs and cached in a directory of the spec's own, so they reach no network either.
 */
class TrustedSourceRegistryTest : FunSpec({

	val registry = TrustedSourceRegistry()

	fun configWith(validation: ValidationConfig) = ResolvedConfig(
		hashAlgorithm = HashAlgorithm.SHA256,
		encryptionAlgorithm = null,
		signatureLevel = SignatureLevel.PADES_BASELINE_B,
		timestampServer = null,
		ocsp = OcspConfig(),
		crl = CrlConfig(),
		validation = validation,
	)

	fun anchor(): ResolvedTrustAnchor =
		ResolvedTrustAnchor(
			fingerprint = "sha256-test",
			type = TrustedCertificateType.ANY,
			der = generateSelfSignedCert().encoded,
		)

	test("composeInto wires nothing and returns no warnings when no TL and no direct anchors") {
		val cv = CommonCertificateVerifier()
		val warnings = registry.composeInto(
			cv,
			configWith(ValidationConfig(useEuLotl = false))
		)
		warnings.shouldBeEmpty()
		cv.trustedCertSources.numberOfCertificates shouldBe 0
	}

	test("composeInto wires only direct trusted anchors and returns no TL warnings") {
		val cv = CommonCertificateVerifier()
		val warnings = registry.composeInto(
			cv,
			configWith(ValidationConfig(useEuLotl = false)),
			directAnchors = listOf(anchor()),
		)
		warnings.shouldBeEmpty()
		cv.trustedCertSources.numberOfCertificates shouldBe 1
	}

	test("composeInto keeps configurations isolated across one shared registry") {
		val cvA = CommonCertificateVerifier()
		registry.composeInto(
			cvA,
			configWith(ValidationConfig(useEuLotl = false)),
			directAnchors = listOf(anchor()),
		)

		val cvB = CommonCertificateVerifier()
		registry.composeInto(
			cvB,
			configWith(ValidationConfig(useEuLotl = false)),
			directAnchors = listOf(anchor(), anchor()),
		)

		cvA.trustedCertSources.numberOfCertificates shouldBe 1
		cvB.trustedCertSources.numberOfCertificates shouldBe 2
	}

	test("cacheExpirationMillis defaults to the shared TL cache expiration constant") {
		TrustedSourceRegistry().cacheExpirationMillis shouldBe DssServiceFactory.TL_CACHE_EXPIRATION_MS
	}

	test("warmUp, refreshAll, forceRefreshAll and shutdown are safe no-ops when no trusted lists are used") {
		registry.warmUp(useEuLotl = false, customTls = emptyList())
		registry.refreshAll()
		registry.forceRefreshAll()
		registry.shutdown()
	}

	test("refreshAll and forceRefreshAll stamp and report neither success nor failure when no sources are retained") {
		val signal = TrustedListRefreshSignal()
		val emptyRegistry = TrustedSourceRegistry(signal)

		emptyRegistry.warmUp(useEuLotl = false, customTls = emptyList())
		emptyRegistry.refreshAll()
		emptyRegistry.forceRefreshAll() shouldBe null

		signal.lastRefreshAt.value shouldBe null
		signal.lastFailure.value shouldBe null
	}

	test("health queries report no trust when no sources are retained") {
		val emptyRegistry = TrustedSourceRegistry()
		emptyRegistry.hasIncompleteTrust() shouldBe false
		emptyRegistry.euLotlTrustLoaded() shouldBe false
	}

	val certDir = tempdir()

	fun listWithSigningCert(path: String) = CustomTrustedListConfig(
		name = "broken-tl",
		source = "https://tl.omnisign.test/tl.xml",
		signingCertPath = path,
	)

	fun unreadableSigningCertWarning(path: String) =
		LocalizableText.of(MessageKey.WARNING_TRUSTED_LIST_SIGNING_CERTIFICATE_UNREADABLE, "broken-tl", path)

	/** Run [block] and return every event the registry's logger emitted meanwhile, down to DEBUG. */
	fun eventsLoggedDuring(block: () -> Unit): List<ILoggingEvent> {
		val logbackLogger = LoggerFactory.getLogger("cz.pizavo.omnisign.data.repository.TrustedSourceRegistry") as Logger
		val previousLevel = logbackLogger.level
		val appender = ListAppender<ILoggingEvent>().also {
			it.start()
			logbackLogger.addAppender(it)
		}
		logbackLogger.level = Level.DEBUG
		try {
			block()
		} finally {
			logbackLogger.level = previousLevel
			logbackLogger.detachAppender(appender)
			appender.stop()
		}
		return appender.list.toList()
	}

	test("composeInto goes on without a list whose signing certificate is missing, and says why") {
		val missing = File(certDir, "missing.cer").path
		val cv = CommonCertificateVerifier()

		val warnings = TrustedSourceRegistry().composeInto(
			cv,
			configWith(ValidationConfig(useEuLotl = false, customTrustedLists = listOf(listWithSigningCert(missing)))),
			directAnchors = listOf(anchor()),
		)

		warnings shouldBe listOf(unreadableSigningCertWarning(missing))
		cv.trustedCertSources.numberOfCertificates shouldBe 1
	}

	test("a signing certificate file that holds no certificate cannot be read either") {
		val notACertificate = File(certDir, "not-a-certificate.cer").apply { writeText("not a certificate") }.path

		val warnings = TrustedSourceRegistry().composeInto(
			CommonCertificateVerifier(),
			configWith(ValidationConfig(useEuLotl = false, customTrustedLists = listOf(listWithSigningCert(notACertificate)))),
		)

		warnings shouldBe listOf(unreadableSigningCertWarning(notACertificate))
	}

	test("warmUp reports a list whose signing certificate cannot be read as failed and takes no trust from it") {
		val signal = TrustedListRefreshSignal()
		val registry = TrustedSourceRegistry(signal)

		registry.warmUp(useEuLotl = false, customTls = listOf(listWithSigningCert(File(certDir, "missing.cer").path)))

		signal.lastFailure.value.shouldBeInstanceOf<TrustedListRefreshFailure.CustomList>().name shouldBe "broken-tl"
		signal.lastRefreshAt.value shouldBe null
		registry.hasIncompleteTrust() shouldBe true
	}

	test("the hard and the scheduled refresh count that list as failed instead of throwing") {
		val signal = TrustedListRefreshSignal()
		val registry = TrustedSourceRegistry(signal)
		registry.warmUp(useEuLotl = false, customTls = listOf(listWithSigningCert(File(certDir, "missing.cer").path)))

		registry.forceRefreshAll().shouldBeInstanceOf<TrustedListRefreshFailure.CustomList>().name shouldBe "broken-tl"
		registry.refreshAll()

		signal.lastRefreshAt.value shouldBe null
		registry.hasIncompleteTrust() shouldBe true
	}

	test("the reason is logged in one line, at WARN once and at DEBUG while it stays the same") {
		val missing = File(certDir, "missing.cer").path
		val registry = TrustedSourceRegistry()

		val events = eventsLoggedDuring {
			registry.warmUp(useEuLotl = false, customTls = listOf(listWithSigningCert(missing)))
			registry.forceRefreshAll()
			registry.refreshAll()
		}

		val aboutCertificate = events.filter { it.formattedMessage.contains("signing certificate") }
		aboutCertificate.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.DEBUG)
		aboutCertificate.first().formattedMessage shouldContain "'broken-tl'"
		aboutCertificate.first().formattedMessage shouldContain missing
		aboutCertificate.forAll { it.throwableProxy shouldBe null }
	}

	test("a new reason is logged at WARN again") {
		val path = File(certDir, "changing.cer").path
		val registry = TrustedSourceRegistry()

		val events = eventsLoggedDuring {
			registry.warmUp(useEuLotl = false, customTls = listOf(listWithSigningCert(path)))
			File(path).writeText("not a certificate")
			registry.refreshAll()
		}

		events.filter { it.formattedMessage.contains("signing certificate") }.map { it.level } shouldBe
			listOf(Level.WARN, Level.WARN)
	}

	val listDir = tempdir()
	val listCacheDir = tempdir()

	/** A registry that caches the lists it loads in a directory of this spec's own. */
	fun isolatedRegistry(signal: TrustedListRefreshSignal = TrustedListRefreshSignal()) =
		TrustedSourceRegistry(signal, cacheDirectory = { listCacheDir })

	/** Write [certificate] to a DER file named [fileName] and return its path. */
	fun derFile(fileName: String, certificate: X509Certificate): String =
		File(listDir, fileName).apply { writeBytes(certificate.encoded) }.path

	/** A trusted list, compiled as `config tl build` compiles one, that names [listed] as a granted CA. */
	fun compiledList(listed: X509Certificate): String {
		val address = TrustedListAddress(
			streetAddress = "Technicka 2",
			locality = "Praha",
			countryName = "CZ",
			postalCode = "16000",
			electronicAddress = "mailto:tl@omnisign.test",
		)
		val service = TrustServiceDraft(
			name = "Registry Test CA",
			typeIdentifier = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
			status = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
			certificatePath = derFile("listed-${listed.serialNumber}.der", listed),
		)
		val provider = TrustServiceProviderDraft(
			name = "OmniSign Test TSP",
			address = address,
			infoUrl = "https://tsp.omnisign.test",
			services = listOf(service),
		)
		return TrustedListCompiler().compile(
			CustomTrustedListDraft(
				name = "Registry test list",
				territory = "CZ",
				schemeOperatorName = "OmniSign Test Operator",
				schemeOperatorAddress = address,
				schemeName = "OmniSign registry test anchors",
				schemeInformationUri = "https://omnisign.test/tl/about",
				trustServiceProviders = listOf(provider),
			)
		)
	}

	/** Sign [xml] as a trusted list is signed, enveloped in XAdES-BASELINE-B, with [keyPair] and its [certificate]. */
	fun signed(xml: String, keyPair: KeyPair, certificate: X509Certificate): String {
		val service = XAdESService(CommonCertificateVerifier())
		val parameters = XAdESSignatureParameters().apply {
			signatureLevel = DssSignatureLevel.XAdES_BASELINE_B
			signaturePackaging = SignaturePackaging.ENVELOPED
			digestAlgorithm = DigestAlgorithm.SHA256
			signingCertificate = CertificateToken(certificate)
		}
		val document = InMemoryDocument(xml.encodeToByteArray())
		val dataToSign = service.getDataToSign(document, parameters)
		val value = Signature.getInstance("SHA256withRSA").run {
			initSign(keyPair.private)
			update(dataToSign.bytes)
			sign()
		}
		return service.signDocument(document, parameters, SignatureValue(SignatureAlgorithm.RSA_SHA256, value))
			.openStream().use { it.readBytes().decodeToString() }
	}

	/** Write [xml] to a file named [fileName] and return the file's `file:` URL. */
	fun listUrl(fileName: String, xml: String): String =
		File(listDir, fileName).apply { writeText(xml) }.toURI().toString()

	/** A configuration that uses only the custom list [name] at [url], verified against [signingCertPath] if given. */
	fun customListConfig(name: String, url: String, signingCertPath: String?) = configWith(
		ValidationConfig(useEuLotl = false, customTrustedLists = listOf(CustomTrustedListConfig(name, url, signingCertPath)))
	)

	/** The warning that the list at [url] is not used because its signature could not be verified. */
	fun unverifiedSignatureWarning(url: String) =
		LocalizableText.of(MessageKey.WARNING_TRUSTED_LIST_SIGNATURE_UNVERIFIED, "1 trusted list", url)

	test("a custom list signed with its configured signing certificate supplies trust without a warning") {
		val signer = generateKeyPair()
		val signerCertificate = selfSignedCertificate(signer, "TL Signer")
		val listed = generateSelfSignedCert()
		val url = listUrl("signed.xml", signed(compiledList(listed), signer, signerCertificate))
		val cv = CommonCertificateVerifier()

		val warnings = isolatedRegistry().composeInto(
			cv,
			customListConfig("signed-tl", url, derFile("signed-signer.der", signerCertificate)),
		)

		warnings.shouldBeEmpty()
		cv.trustedCertSources.certificates shouldBe listOf(CertificateToken(listed))
	}

	test("a custom list signed with another key supplies no trust, and the warning says why") {
		val impostor = generateKeyPair()
		val url = listUrl(
			"impostor.xml",
			signed(compiledList(generateSelfSignedCert()), impostor, selfSignedCertificate(impostor, "Impostor")),
		)
		val signal = TrustedListRefreshSignal()
		val registry = isolatedRegistry(signal)
		val cv = CommonCertificateVerifier()

		val warnings = registry.composeInto(
			cv,
			customListConfig("impostor-tl", url, derFile("impostor-signer.der", generateSelfSignedCert())),
		)

		warnings shouldBe listOf(unverifiedSignatureWarning(url))
		cv.trustedCertSources.numberOfCertificates shouldBe 0
		signal.lastFailure.value.shouldBeInstanceOf<TrustedListRefreshFailure.CustomList>().name shouldBe "impostor-tl"
		registry.hasIncompleteTrust() shouldBe true
	}

	test("an unsigned custom list supplies no trust when a signing certificate is configured") {
		val url = listUrl("unsigned-checked.xml", compiledList(generateSelfSignedCert()))
		val cv = CommonCertificateVerifier()

		val warnings = isolatedRegistry().composeInto(
			cv,
			customListConfig("unsigned-tl", url, derFile("unsigned-signer.der", generateSelfSignedCert())),
		)

		warnings shouldBe listOf(unverifiedSignatureWarning(url))
		cv.trustedCertSources.numberOfCertificates shouldBe 0
	}

	test("a custom list configured without a signing certificate is used whatever its signature") {
		val listed = generateSelfSignedCert()
		val url = listUrl("unsigned-unchecked.xml", compiledList(listed))
		val cv = CommonCertificateVerifier()

		val warnings = isolatedRegistry().composeInto(cv, customListConfig("unchecked-tl", url, null))

		warnings.shouldBeEmpty()
		cv.trustedCertSources.certificates shouldBe listOf(CertificateToken(listed))
	}

	test("a hard refresh drops the trust of a list whose listed certificate was swapped after it was signed") {
		val signer = generateKeyPair()
		val signerCertificate = selfSignedCertificate(signer, "TL Signer")
		val listed = generateSelfSignedCert()
		val signedXml = signed(compiledList(listed), signer, signerCertificate)
		val listedBase64 = Base64.getEncoder().encodeToString(listed.encoded)
		signedXml shouldContain listedBase64
		val file = File(listDir, "swapped.xml").apply { writeText(signedXml) }
		val url = file.toURI().toString()
		val config = customListConfig("swapped-tl", url, derFile("swapped-signer.der", signerCertificate))
		val registry = isolatedRegistry()
		registry.composeInto(CommonCertificateVerifier(), config).shouldBeEmpty()

		file.writeText(signedXml.replace(listedBase64, Base64.getEncoder().encodeToString(generateSelfSignedCert().encoded)))
		val failure = registry.forceRefreshAll()

		failure.shouldBeInstanceOf<TrustedListRefreshFailure.CustomList>().name shouldBe "swapped-tl"
		val cv = CommonCertificateVerifier()
		registry.composeInto(cv, config) shouldBe listOf(unverifiedSignatureWarning(url))
		cv.trustedCertSources.numberOfCertificates shouldBe 0
	}

	test("a list whose signature cannot be verified is logged at WARN once and at DEBUG while that stays so") {
		val impostor = generateKeyPair()
		val url = listUrl(
			"logged.xml",
			signed(compiledList(generateSelfSignedCert()), impostor, selfSignedCertificate(impostor, "Impostor")),
		)
		val list = CustomTrustedListConfig("logged-tl", url, derFile("logged-signer.der", generateSelfSignedCert()))
		val registry = isolatedRegistry()

		val events = eventsLoggedDuring {
			registry.warmUp(useEuLotl = false, customTls = listOf(list))
			registry.refreshAll()
			registry.forceRefreshAll()
		}

		val aboutSignature = events.filter { it.formattedMessage.contains("signature could not be verified") }
		aboutSignature.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.DEBUG)
		aboutSignature.first().formattedMessage shouldContain url
	}
}) {
	companion object {
		/** Generate a throwaway self-signed X.509 certificate for direct-trust tests. */
		fun generateSelfSignedCert(): X509Certificate = selfSignedCertificate(generateKeyPair())

		/** Generate a throwaway 2048-bit RSA key pair. */
		fun generateKeyPair(): KeyPair =
			KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

		/**
		 * Issue a self-signed X.509 certificate named [commonName] for [keyPair]. It is valid from a
		 * day ago for a year, so a signature made with the key now falls within its validity.
		 */
		fun selfSignedCertificate(keyPair: KeyPair, commonName: String = "Registry Test"): X509Certificate {
			val subject = X500Name("CN=$commonName")
			val now = Clock.System.now()
			val builder = JcaX509v3CertificateBuilder(
				subject,
				BigInteger.valueOf(System.nanoTime()),
				Date.from((now - 1.days).toJavaInstant()),
				Date.from((now + 365.days).toJavaInstant()),
				subject,
				keyPair.public,
			)
			val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
			return JcaX509CertificateConverter().getCertificate(builder.build(signer))
		}
	}
}
