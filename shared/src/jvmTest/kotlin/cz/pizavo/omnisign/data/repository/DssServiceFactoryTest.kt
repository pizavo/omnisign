package cz.pizavo.omnisign.data.repository

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.service.CredentialStore
import eu.europa.esig.dss.model.job.DownloadInfoRecord
import eu.europa.esig.dss.model.tsl.LOTLInfo
import eu.europa.esig.dss.model.tsl.TLInfo
import eu.europa.esig.dss.model.tsl.TLValidationJobSummary
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.validation.job.sync.SynchronizationStrategy
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

/**
 * Verifies [DssServiceFactory] builds TSP sources, certificate verifiers,
 * and PDF object factories with expected configuration wiring.
 *
 * The AIA tests ask each verifier's AIA source for the issuer of a certificate that points to a file
 * or to an HTTP server on the loopback interface, which stands in for any internal address.
 *
 * The trusted-list warning tests hand [DssServiceFactory.collectTlWarnings] a mocked job summary and
 * a strategy that keeps out the lists they name, as one does whose signature could not be verified.
 */
class DssServiceFactoryTest : FunSpec({
	
	val credentialStore: CredentialStore = mockk(relaxed = true)
	val factory = DssServiceFactory(credentialStore)
	
	/** Run [block] and return the warnings [DssServiceFactory] logged meanwhile. */
	fun warningsLoggedDuring(block: () -> Unit): List<String> {
		val logbackLogger = LoggerFactory.getLogger(DssServiceFactory::class.java) as Logger
		val appender = ListAppender<ILoggingEvent>().also {
			it.start()
			logbackLogger.addAppender(it)
		}
		try {
			block()
		} finally {
			logbackLogger.detachAppender(appender)
			appender.stop()
		}
		return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
	}
	
	fun minimalConfig(checkRevocation: Boolean = true) = ResolvedConfig(
		hashAlgorithm = HashAlgorithm.SHA256,
		encryptionAlgorithm = null,
		signatureLevel = SignatureLevel.PADES_BASELINE_B,
		timestampServer = null,
		ocsp = OcspConfig(),
		crl = CrlConfig(),
		validation = ValidationConfig(checkRevocation = checkRevocation, useEuLotl = false),
	)
	
	// ── buildTspSource ────────────────────────────────────────────────────────
	
	test("buildTspSource creates a source pointing to the configured URL") {
		val tsp = factory.buildTspSource(TimestampServerConfig(url = "http://tsa.example.com"))
		tsp.shouldNotBeNull()
	}
	
	test("buildTspSource resolves runtime password over credential store") {
		every { credentialStore.getPassword(any(), any()) } returns "stored"
		
		val tsp = factory.buildTspSource(
			TimestampServerConfig(
				url = "http://tsa.example.com",
				username = "user",
				runtimePassword = cz.pizavo.omnisign.domain.model.value.Sensitive("runtime"),
			)
		)
		tsp.shouldNotBeNull()
	}
	
	test("buildTspSource falls back to credential store when no runtime password") {
		every { credentialStore.getPassword("omnisign-tsa", "mykey") } returns "stored-pw"
		
		val tsp = factory.buildTspSource(
			TimestampServerConfig(
				url = "http://tsa.example.com",
				username = "user",
				credentialKey = "mykey",
			)
		)
		tsp.shouldNotBeNull()
	}
	
	test("buildTspSource warns once per key when the saved TSA password cannot be found") {
		val store: CredentialStore = mockk {
			every { getPassword("omnisign-tsa", "tsa-user") } returns null
			every { isPersistent } returns false
		}
		val freshFactory = DssServiceFactory(store)
		val tsConfig = TimestampServerConfig(
			url = "http://tsa.example.com",
			username = "tsa-user",
			credentialKey = "tsa-user",
		)
		
		val warnings = warningsLoggedDuring {
			freshFactory.buildTspSource(tsConfig)
			freshFactory.buildTspSource(tsConfig)
		}
		
		warnings.shouldHaveSize(1)
		warnings.single() shouldContain "'tsa-user'"
		warnings.single() shouldContain "No keyring service was found"
	}
	
	test("buildTspSource does not warn when the saved TSA password is found") {
		val store: CredentialStore = mockk {
			every { getPassword("omnisign-tsa", "tsa-user") } returns "stored-pw"
		}
		
		val warnings = warningsLoggedDuring {
			DssServiceFactory(store).buildTspSource(
				TimestampServerConfig(url = "http://tsa.example.com", username = "tsa-user", credentialKey = "tsa-user")
			)
		}
		
		warnings.shouldBeEmpty()
	}
	
	// ── buildSigningCertificateVerifier ───────────────────────────────────────
	
	test("signing verifier with null config returns lenient verifier with suppressed alerts") {
		val result = factory.buildSigningCertificateVerifier(null)
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.verifier.alertOnUncoveredPOE shouldBe null
		result.verifier.alertOnInvalidTimestamp shouldBe null
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldBe null
		result.verifier.alertOnRevokedCertificate shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}
	
	test("signing verifier with revocation disabled returns suppressed alerts") {
		val result = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = false))
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}
	
	test("signing verifier with revocation enabled returns wired verifier") {
		val result = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = true))
		result.verifier.aiaSource shouldNotBe null
		result.verifier.ocspSource shouldNotBe null
		result.verifier.crlSource shouldNotBe null
		result.verifier.isCheckRevocationForUntrustedChains shouldBe false
	}
	
	test("signing verifier suppresses missing-revocation and fresh-revocation alerts") {
		val result = factory.buildSigningCertificateVerifier(minimalConfig(checkRevocation = true))
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldBe null
	}
	
	test("signing verifier keeps actionable alerts active") {
		val statusAlert = CollectingStatusAlert()
		val result = factory.buildSigningCertificateVerifier(minimalConfig()) { statusAlert }
		result.verifier.alertOnUncoveredPOE shouldBe statusAlert
		result.verifier.alertOnInvalidTimestamp shouldBe statusAlert
		result.verifier.alertOnRevokedCertificate shouldBe statusAlert
	}
	
	test("signing verifier keeps the expired-certificate alert by default") {
		val result = factory.buildSigningCertificateVerifier(minimalConfig())
		result.verifier.alertOnExpiredCertificate shouldNotBe null
	}

	test("signing verifier disables the expired-certificate alert when allowed") {
		val config = minimalConfig().copy(
			validation = minimalConfig().validation.copy(allowExpiredCertificate = true),
		)
		val result = factory.buildSigningCertificateVerifier(config)
		result.verifier.alertOnExpiredCertificate shouldBe null
	}

	test("signing verifier disables the expired-certificate alert even with revocation off") {
		val config = minimalConfig(checkRevocation = false).copy(
			validation = ValidationConfig(checkRevocation = false, useEuLotl = false, allowExpiredCertificate = true),
		)
		val result = factory.buildSigningCertificateVerifier(config)
		result.verifier.alertOnExpiredCertificate shouldBe null
	}

	test("validation verifier silences the expired-certificate alert, which DSS defaults to throwing") {
		val config = minimalConfig().copy(
			validation = minimalConfig().validation.copy(allowExpiredCertificate = true),
		)
		val result = factory.buildValidationCertificateVerifier(config)
		result.verifier.alertOnExpiredCertificate shouldBe null
		result.verifier.alertOnNotYetValidCertificate shouldBe null
	}

	// ── buildExtendCertificateVerifier ────────────────────────────────────────

	test("extend verifier reports missing revocation data, unlike the signing verifier") {
		val statusAlert = CollectingStatusAlert()
		val result = factory.buildExtendCertificateVerifier(minimalConfig(checkRevocation = true)) { statusAlert }
		result.verifier.alertOnMissingRevocationData shouldBe statusAlert
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldBe statusAlert
	}

	test("extend verifier keeps the signing verifier's other alerts and wiring") {
		val statusAlert = CollectingStatusAlert()
		val result = factory.buildExtendCertificateVerifier(minimalConfig(checkRevocation = true)) { statusAlert }
		result.verifier.alertOnUncoveredPOE shouldBe statusAlert
		result.verifier.alertOnInvalidTimestamp shouldBe statusAlert
		result.verifier.alertOnRevokedCertificate shouldBe statusAlert
		result.verifier.aiaSource shouldNotBe null
		result.verifier.ocspSource shouldNotBe null
		result.verifier.crlSource shouldNotBe null
		result.verifier.isCheckRevocationForUntrustedChains shouldBe false
	}

	test("extend verifier with revocation disabled suppresses every alert") {
		val result = factory.buildExtendCertificateVerifier(minimalConfig(checkRevocation = false))
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldBe null
		result.verifier.alertOnUncoveredPOE shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}

	test("extend verifier with null config suppresses every alert") {
		val result = factory.buildExtendCertificateVerifier(null)
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}

	test("extend verifier honours allowExpiredCertificate like the signing verifier") {
		val config = minimalConfig().copy(
			validation = minimalConfig().validation.copy(allowExpiredCertificate = true),
		)
		factory.buildExtendCertificateVerifier(config).verifier.alertOnExpiredCertificate shouldBe null
		factory.buildExtendCertificateVerifier(minimalConfig()).verifier.alertOnExpiredCertificate shouldNotBe null
	}

	// ── buildValidationCertificateVerifier ────────────────────────────────────
	
	test("validation verifier with null config returns suppressed alerts and empty warnings") {
		val result = factory.buildValidationCertificateVerifier(null)
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}
	
	test("validation verifier with revocation disabled returns suppressed alerts") {
		val result = factory.buildValidationCertificateVerifier(minimalConfig(checkRevocation = false))
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.tlWarnings.shouldBeEmpty()
	}
	
	test("validation verifier with revocation enabled but no TLs returns empty TL warnings") {
		val config = minimalConfig(checkRevocation = true).copy(
			validation = ValidationConfig(
				checkRevocation = true,
				useEuLotl = false,
				customTrustedLists = emptyList(),
			)
		)
		val result = factory.buildValidationCertificateVerifier(config)
		result.verifier.alertOnUncoveredPOE shouldNotBe null
		result.tlWarnings.shouldBeEmpty()
	}

	test("validation verifier reports only revocation coverage, leaving the report to say the rest") {
		val result = factory.buildValidationCertificateVerifier(minimalConfig(checkRevocation = true))
		result.verifier.alertOnUncoveredPOE shouldNotBe null
		result.verifier.alertOnNoRevocationAfterBestSignatureTime shouldNotBe null
		result.verifier.alertOnMissingRevocationData shouldBe null
		result.verifier.alertOnInvalidTimestamp shouldBe null
		result.verifier.alertOnRevokedCertificate shouldBe null
	}
	
	// ── buildCertificateVerifier (simple timeout-based) ──────────────────────
	
	test("simple certificate verifier has wired AIA, OCSP, and CRL sources") {
		val cv = factory.buildCertificateVerifier()
		cv.aiaSource shouldNotBe null
		cv.ocspSource shouldNotBe null
		cv.crlSource shouldNotBe null
	}
	
	// ── buildPdfObjectFactory ────────────────────────────────────────────────
	
	test("buildPdfObjectFactory returns a non-null PdfBox factory") {
		factory.buildPdfObjectFactory().shouldNotBeNull()
	}
	
	// ── companion utilities ──────────────────────────────────────────────────
	
	test("EU LOTL URL points to the EC trusted list endpoint") {
		DssServiceFactory.EU_LOTL_URL shouldContain "ec.europa.eu"
		DssServiceFactory.EU_LOTL_URL shouldContain "lotl"
	}
	
	test("signing verifier with direct anchors has wired trusted source") {
		val selfSigned = generateSelfSignedCert()
		val anchor = cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor(
			fingerprint = "sha256-test",
			type = TrustedCertificateType.ANY,
			der = selfSigned.encoded,
		)
		val config = minimalConfig().copy(validation = ValidationConfig(useEuLotl = false))
		val result = factory.buildSigningCertificateVerifier(config, directAnchors = listOf(anchor))
		result.verifier.trustedCertSources.shouldNotBeNull()
		result.verifier.trustedCertSources.numberOfCertificates shouldBe 1
	}

	// ── AIA downloads ────────────────────────────────────────────────────────

	val issuerDer = generateSelfSignedCert().encoded
	val issuerRequests = AtomicInteger()
	val issuerServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/issuer.cer") { exchange ->
			issuerRequests.incrementAndGet()
			exchange.sendResponseHeaders(200, issuerDer.size.toLong())
			exchange.responseBody.use { it.write(issuerDer) }
		}
		start()
	}
	val loopbackIssuerUrl = "http://127.0.0.1:${issuerServer.address.port}/issuer.cer"
	afterSpec { issuerServer.stop(0) }

	/** Every kind of verifier [factory] builds, by a name for the test titles. */
	fun everyVerifier(factory: DssServiceFactory): Map<String, CommonCertificateVerifier> = mapOf(
		"signing" to factory.buildSigningCertificateVerifier(minimalConfig()).verifier,
		"signing without a config" to factory.buildSigningCertificateVerifier(null).verifier,
		"extension without revocation" to
			factory.buildExtendCertificateVerifier(minimalConfig(checkRevocation = false)).verifier,
		"validation" to factory.buildValidationCertificateVerifier(minimalConfig()).verifier,
		"validation without revocation" to
			factory.buildValidationCertificateVerifier(minimalConfig(checkRevocation = false)).verifier,
		"validation without a config" to factory.buildValidationCertificateVerifier(null).verifier,
		"level inspection" to factory.buildLevelInspectionVerifier(minimalConfig()).verifier,
		"level inspection without a config" to factory.buildLevelInspectionVerifier(null).verifier,
		"lenient" to factory.buildCertificateVerifier(),
	)

	test("no verifier follows a file URL to an issuer") {
		val issuerFile = tempfile(suffix = ".cer").apply { writeBytes(issuerDer) }
		val leaf = certificateWithCaIssuers(issuerFile.toURI().toString())

		everyVerifier(DssServiceFactory(credentialStore, aiaFetchPolicy = AiaFetchPolicy(allowPrivateAddresses = true)))
			.forEach { (name, verifier) ->
				withClue(name) { verifier.aiaSource.getCertificatesByAIA(leaf).shouldBeEmpty() }
			}
	}

	test("no verifier downloads an issuer from an internal address by default") {
		val leaf = certificateWithCaIssuers(loopbackIssuerUrl)
		val before = issuerRequests.get()

		everyVerifier(DssServiceFactory(credentialStore)).forEach { (name, verifier) ->
			withClue(name) { verifier.aiaSource.getCertificatesByAIA(leaf).shouldBeEmpty() }
		}
		issuerRequests.get() shouldBe before
	}

	test("every verifier downloads an issuer from an internal address when the policy allows it") {
		val leaf = certificateWithCaIssuers(loopbackIssuerUrl)

		everyVerifier(DssServiceFactory(credentialStore, aiaFetchPolicy = AiaFetchPolicy(allowPrivateAddresses = true)))
			.forEach { (name, verifier) ->
				withClue(name) { verifier.aiaSource.getCertificatesByAIA(leaf) shouldHaveSize 1 }
			}
	}

	/** A download record of a list that was [downloaded], or that could not be. */
	fun downloadRecord(downloaded: Boolean): DownloadInfoRecord = mockk {
		every { isError } returns !downloaded
		every { isResultExist } returns downloaded
	}

	/** A trusted list at [address] that was [downloaded], or that could not be. */
	fun trustedList(address: String, downloaded: Boolean = true): TLInfo {
		val record = downloadRecord(downloaded)
		return mockk {
			every { url } returns address
			every { downloadCacheInfo } returns record
		}
	}

	/** A list of trusted lists at [address] that points to [members] and was [downloaded], or that could not be. */
	fun listOfTrustedLists(address: String, vararg members: TLInfo, downloaded: Boolean = true): LOTLInfo {
		val record = downloadRecord(downloaded)
		return mockk {
			every { url } returns address
			every { downloadCacheInfo } returns record
			every { tlInfos } returns members.toList()
		}
	}

	/** A job summary of [lotls] and of the standalone lists [others]. */
	fun summaryOf(lotls: List<LOTLInfo>, others: List<TLInfo>): TLValidationJobSummary = mockk {
		every { lotlInfos } returns lotls
		every { otherTLInfos } returns others
	}

	/** A strategy that keeps out the lists at [rejected], as it keeps out one whose signature could not be verified. */
	fun rejecting(vararg rejected: String) = object : SynchronizationStrategy<TLInfo, LOTLInfo> {
		override fun canBeSynchronized(document: TLInfo): Boolean = document.url !in rejected

		override fun canBeSynchronized(documentList: LOTLInfo): Boolean = documentList.url !in rejected
	}

	test("downloadedAnyList tells a refresh that downloaded a list from one that downloaded none") {
		val offline = trustedList("https://offline.omnisign.test/tl.xml", downloaded = false)
		val lotlWithUnreachableMember = listOfTrustedLists(
			"https://lotl.omnisign.test/lotl.xml",
			trustedList("https://unreachable.omnisign.test/tl.xml", downloaded = false),
		)

		DssServiceFactory.downloadedAnyList(
			summaryOf(listOf(listOfTrustedLists("https://lotl.omnisign.test/lotl.xml", downloaded = false)), listOf(offline))
		) shouldBe false
		DssServiceFactory.downloadedAnyList(summaryOf(listOf(lotlWithUnreachableMember), listOf(offline))) shouldBe true
		DssServiceFactory.downloadedAnyList(
			summaryOf(emptyList(), listOf(offline, trustedList("https://sound.omnisign.test/tl.xml")))
		) shouldBe true
	}

	test("collectTlWarnings tells the lists that could not be downloaded from those whose signature could not be verified") {
		val summary = summaryOf(
			lotls = emptyList(),
			others = listOf(
				trustedList("https://offline.omnisign.test/tl.xml", downloaded = false),
				trustedList("https://forged.omnisign.test/tl.xml"),
				trustedList("https://sound.omnisign.test/tl.xml"),
			),
		)

		DssServiceFactory.collectTlWarnings(summary, rejecting("https://forged.omnisign.test/tl.xml")) shouldBe listOf(
			LocalizableText.of(MessageKey.WARNING_TRUSTED_LIST_REFRESH_INCOMPLETE, "1 trusted list", "offline.omnisign.test"),
			LocalizableText.of(MessageKey.WARNING_TRUSTED_LIST_SIGNATURE_UNVERIFIED, "1 trusted list", "forged.omnisign.test"),
		)
	}

	test("collectTlWarnings names a list of trusted lists that is kept out alone, not the lists it points to") {
		val summary = summaryOf(
			lotls = listOf(
				listOfTrustedLists(
					"https://lotl.omnisign.test/lotl.xml",
					trustedList("https://member.omnisign.test/tl.xml"),
					trustedList("https://unreachable.omnisign.test/tl.xml", downloaded = false),
				),
			),
			others = emptyList(),
		)

		DssServiceFactory.collectTlWarnings(summary, rejecting("https://lotl.omnisign.test/lotl.xml")) shouldBe listOf(
			LocalizableText.of(MessageKey.WARNING_TRUSTED_LIST_SIGNATURE_UNVERIFIED, "1 trusted list", "lotl.omnisign.test"),
		)
	}

	test("collectTlWarnings names the lists a list of trusted lists in use points to that are kept out") {
		val summary = summaryOf(
			lotls = listOf(
				listOfTrustedLists(
					"https://lotl.omnisign.test/lotl.xml",
					trustedList("https://forged-a.omnisign.test/tl.xml"),
					trustedList("https://forged-b.omnisign.test/tl.xml"),
					trustedList("https://sound.omnisign.test/tl.xml"),
				),
			),
			others = emptyList(),
		)

		val rejected = rejecting("https://forged-a.omnisign.test/tl.xml", "https://forged-b.omnisign.test/tl.xml")
		DssServiceFactory.collectTlWarnings(summary, rejected) shouldBe listOf(
			LocalizableText.of(
				MessageKey.WARNING_TRUSTED_LIST_SIGNATURE_UNVERIFIED,
				"2 trusted lists",
				"forged-a.omnisign.test, forged-b.omnisign.test",
			),
		)
	}
}) {
	companion object {
		/**
		 * Generate a self-signed X.509 certificate for testing purposes.
		 */
		fun generateSelfSignedCert(): java.security.cert.X509Certificate {
			val keyPairGen = java.security.KeyPairGenerator.getInstance("RSA")
			keyPairGen.initialize(2048)
			val keyPair = keyPairGen.generateKeyPair()
			
			val subject = org.bouncycastle.asn1.x500.X500Name("CN=Test")
			val serial = java.math.BigInteger.valueOf(System.currentTimeMillis())
			val now = Clock.System.now()
			val notBefore = Date.from(now.toJavaInstant())
			val notAfter = Date.from((now + 365.days).toJavaInstant())
			
			val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
				subject, serial, notBefore, notAfter, subject, keyPair.public
			)
			val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
				.build(keyPair.private)
			val holder = builder.build(signer)
			
			return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
				.getCertificate(holder)
		}

		/**
		 * A certificate whose issuer is nowhere to be found, with an Authority Information Access
		 * extension pointing DSS to [caIssuerUrls] for it.
		 */
		fun certificateWithCaIssuers(vararg caIssuerUrls: String): CertificateToken {
			val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
			val now = Clock.System.now()
			val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
				X500Name("CN=Missing Issuer"),
				java.math.BigInteger.valueOf(System.nanoTime()),
				Date.from(now.toJavaInstant()),
				Date.from((now + 365.days).toJavaInstant()),
				X500Name("CN=Leaf"),
				keyPair.public,
			).addExtension(
				Extension.authorityInfoAccess,
				false,
				AuthorityInformationAccess(
					caIssuerUrls.map { url ->
						AccessDescription(
							AccessDescription.id_ad_caIssuers,
							GeneralName(GeneralName.uniformResourceIdentifier, url),
						)
					}.toTypedArray()
				),
			)
			val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
				.build(keyPair.private)
			return CertificateToken(
				org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(signer))
			)
		}
	}
}

