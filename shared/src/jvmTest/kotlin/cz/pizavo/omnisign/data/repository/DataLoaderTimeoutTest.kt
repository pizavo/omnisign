package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.DistributionPoint
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.toJavaInstant

/**
 * Verifies that every DSS data loader [DssServiceFactory] and [TrustedSourceRegistry] build stops
 * waiting on a server that takes the connection and never answers once the configured timeout has
 * passed, instead of after DSS's default response timeout of 60 seconds: the timestamp source, the
 * OCSP and CRL sources of each verifier that fetches revocation data, and a trusted list's download.
 *
 * Each request goes to a [SilentServer], which counts the connections it takes, so a request that
 * never reached it cannot pass for one that gave up in time. A verifier waits on an OCSP responder
 * for the OCSP timeout and on a CRL's host for the CRL timeout, however short the other one is.
 */
class DataLoaderTimeoutTest : FunSpec({

	val factory = DssServiceFactory(mockk<CredentialStore>(relaxed = true))
	val silentServer = SilentServer()
	afterSpec { silentServer.close() }

	val leaf = certificateWithRevocationAt(silentServer.url)
	val issuer = selfSignedCertificate()

	/**
	 * A configuration that checks revocation, using no trusted list but [customTrustedLists], whose
	 * OCSP and CRL requests get [ocspTimeout] and [crlTimeout].
	 */
	fun configWith(
		ocspTimeout: Int,
		crlTimeout: Int,
		customTrustedLists: List<CustomTrustedListConfig> = emptyList(),
	) = ResolvedConfig(
		hashAlgorithm = HashAlgorithm.SHA256,
		encryptionAlgorithm = null,
		signatureLevel = SignatureLevel.PADES_BASELINE_B,
		timestampServer = null,
		ocsp = OcspConfig(timeout = ocspTimeout),
		crl = CrlConfig(timeout = crlTimeout),
		validation = ValidationConfig(checkRevocation = true, useEuLotl = false, customTrustedLists = customTrustedLists),
	)

	/** Each verifier [factory] builds from [config] that fetches revocation data, by a name for the clues. */
	fun configuredVerifiers(config: ResolvedConfig): Map<String, CommonCertificateVerifier> = mapOf(
		"signing" to factory.buildSigningCertificateVerifier(config).verifier,
		"extension" to factory.buildExtendCertificateVerifier(config).verifier,
		"validation" to factory.buildValidationCertificateVerifier(config).verifier,
	)

	/** How long [fetch] waited, once [silentServer] is known to have taken its connection. */
	suspend fun waitOnSilentServer(fetch: () -> Unit): Duration {
		val before = silentServer.accepted
		val waited = measureTime { runCatching { fetch() } }
		eventually(ACCEPT_GRACE) { silentServer.accepted shouldBeGreaterThan before }
		return waited
	}

	test("applyTimeout gives a loader the timeout for connecting, its pool, the response and each read") {
		val loader = CommonsDataLoader().apply { DssServiceFactory.applyTimeout(this, 1_234) }

		loader.timeoutConnection shouldBe 1_234
		loader.timeoutConnectionRequest shouldBe 1_234
		loader.timeoutResponse shouldBe 1_234
		loader.timeoutSocket shouldBe 1_234
	}

	test("a timestamp source stops waiting for a server that never answers once its timeout has passed") {
		val source = factory.buildTspSource(TimestampServerConfig(url = "${silentServer.url}/tsa", timeout = SHORT_TIMEOUT_MS))

		waitOnSilentServer { source.getTimeStampResponse(DigestAlgorithm.SHA256, ByteArray(32)) } shouldBeLessThan WAIT_LIMIT
	}

	test("every verifier that fetches revocation data stops waiting for a responder that never answers") {
		configuredVerifiers(configWith(SHORT_TIMEOUT_MS, SHORT_TIMEOUT_MS)).forEach { (name, verifier) ->
			withClue("$name, OCSP") {
				waitOnSilentServer { verifier.ocspSource.getRevocationToken(leaf, issuer) } shouldBeLessThan WAIT_LIMIT
			}
			withClue("$name, CRL") {
				waitOnSilentServer { verifier.crlSource.getRevocationToken(leaf, issuer) } shouldBeLessThan WAIT_LIMIT
			}
		}
	}

	test("a verifier waits on an OCSP responder for the OCSP timeout, however short the CRL timeout") {
		configuredVerifiers(configWith(ocspTimeout = LONGER_TIMEOUT_MS, crlTimeout = SHORT_TIMEOUT_MS))
			.forEach { (name, verifier) ->
				withClue(name) {
					val waited = waitOnSilentServer { verifier.ocspSource.getRevocationToken(leaf, issuer) }

					waited shouldBeGreaterThanOrEqualTo LONGER_WAIT_FLOOR
					waited shouldBeLessThan WAIT_LIMIT
				}
			}
	}

	test("a verifier waits on a CRL's host for the CRL timeout, however short the OCSP timeout") {
		configuredVerifiers(configWith(ocspTimeout = SHORT_TIMEOUT_MS, crlTimeout = LONGER_TIMEOUT_MS))
			.forEach { (name, verifier) ->
				withClue(name) {
					val waited = waitOnSilentServer { verifier.crlSource.getRevocationToken(leaf, issuer) }

					waited shouldBeGreaterThanOrEqualTo LONGER_WAIT_FLOOR
					waited shouldBeLessThan WAIT_LIMIT
				}
			}
	}

	test("a trusted list's download stops waiting for a host that never answers once the fetch timeout has passed") {
		val cacheDir = tempdir()
		val registry = TrustedSourceRegistry(cacheDirectory = { cacheDir }, fetchTimeoutMillis = SHORT_TIMEOUT_MS)
		val config = configWith(
			SHORT_TIMEOUT_MS,
			SHORT_TIMEOUT_MS,
			listOf(CustomTrustedListConfig("silent-tl", "${silentServer.url}/tl.xml", null)),
		)

		waitOnSilentServer { registry.composeInto(CommonCertificateVerifier(), config) } shouldBeLessThan WAIT_LIMIT
	}
}) {
	companion object {
		/** The timeout the tests give a request that should stop waiting soon, in milliseconds. */
		private const val SHORT_TIMEOUT_MS = 500

		/** The longer of two timeouts a test gives a verifier, in milliseconds. */
		private const val LONGER_TIMEOUT_MS = 2_000

		/** How long a request may take before the test calls it unbounded: far below DSS's 60 seconds. */
		private val WAIT_LIMIT = 20.seconds

		/** The least a request given [LONGER_TIMEOUT_MS] waits, well above what [SHORT_TIMEOUT_MS] takes. */
		private val LONGER_WAIT_FLOOR = 1_500.milliseconds

		/** How long the [SilentServer]'s accepting thread may take to count a connection. */
		private val ACCEPT_GRACE = 5.seconds

		/**
		 * A certificate that sends DSS to [baseUrl] for its revocation data: to a CRL at `ca.crl` under it
		 * in its CRL distribution points, and to an OCSP responder at `ocsp` under it in its Authority
		 * Information Access extension. It names `CN=Test` as its issuer, as [selfSignedCertificate] is.
		 */
		fun certificateWithRevocationAt(baseUrl: String): CertificateToken {
			val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
			val now = Clock.System.now()
			val builder = JcaX509v3CertificateBuilder(
				X500Name("CN=Test"),
				BigInteger.valueOf(System.nanoTime()),
				Date.from(now.toJavaInstant()),
				Date.from((now + 365.days).toJavaInstant()),
				X500Name("CN=Leaf"),
				keyPair.public,
			).addExtension(
				Extension.cRLDistributionPoints,
				false,
				CRLDistPoint(
					arrayOf(
						DistributionPoint(
							DistributionPointName(GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, "$baseUrl/ca.crl"))),
							null,
							null,
						),
					),
				),
			).addExtension(
				Extension.authorityInfoAccess,
				false,
				AuthorityInformationAccess(
					AccessDescription(AccessDescription.id_ad_ocsp, GeneralName(GeneralName.uniformResourceIdentifier, "$baseUrl/ocsp")),
				),
			)
			val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
			return CertificateToken(JcaX509CertificateConverter().getCertificate(builder.build(signer)))
		}

		/** A self-signed certificate named `CN=Test`, the issuer [certificateWithRevocationAt] names. */
		fun selfSignedCertificate(): CertificateToken = CertificateToken(DssServiceFactoryTest.generateSelfSignedCert())
	}
}
