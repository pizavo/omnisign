package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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

	test("refreshAll and forceRefreshAll stamp neither success nor failure when no sources are retained") {
		val signal = TrustedListRefreshSignal()
		val emptyRegistry = TrustedSourceRegistry(signal)

		emptyRegistry.warmUp(useEuLotl = false, customTls = emptyList())
		emptyRegistry.refreshAll()
		emptyRegistry.forceRefreshAll()

		signal.lastRefreshAt.value shouldBe null
		signal.lastFailure.value shouldBe null
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
