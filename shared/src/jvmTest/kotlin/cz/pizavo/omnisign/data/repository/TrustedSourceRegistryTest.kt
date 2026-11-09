package cz.pizavo.omnisign.data.repository

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

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
}) {
	companion object {
		/**
		 * Generate a throwaway self-signed X.509 certificate for direct-trust tests.
		 */
		fun generateSelfSignedCert(): java.security.cert.X509Certificate {
			val keyPair = java.security.KeyPairGenerator.getInstance("RSA")
				.apply { initialize(2048) }
				.generateKeyPair()

			val subject = org.bouncycastle.asn1.x500.X500Name("CN=Registry Test")
			val serial = java.math.BigInteger.valueOf(System.nanoTime())
			val now = Clock.System.now()

			val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
				subject,
				serial,
				java.util.Date.from(now.toJavaInstant()),
				java.util.Date.from((now + 365.days).toJavaInstant()),
				subject,
				keyPair.public,
			)
			val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
				.build(keyPair.private)
			return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
				.getCertificate(builder.build(signer))
		}
	}
}
