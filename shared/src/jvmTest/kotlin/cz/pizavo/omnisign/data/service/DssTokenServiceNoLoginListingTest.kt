package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Verifies that [DssTokenService.listCertificatesNoLogin] lists only the certificates of the
 * slots that hold the token it is asked about, and that it gives its probe the configured probe
 * timeout.
 *
 * The `--certs` probe of a library walks every token-present slot, and other tokens on the same
 * middleware sit in some of them. Its output is scripted on a [Pkcs11Prober] mock, whose identity
 * lines are parsed by the real [Pkcs11SubprocessProber.parseIdentities], and the tokens asked
 * about are the ones [Pkcs11TokenInfoDeduplicator] builds from those lines, as discovery does.
 */
class DssTokenServiceNoLoginListingTest : FunSpec({

	val libraryPath = "/usr/lib/middleware-pkcs11.so"
	val nul = Char(0)
	val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }

	/** A self-signed certificate for [commonName] with the serial number [serial]. */
	fun certificate(commonName: String, serial: Long): X509Certificate {
		val keyPair = generator.generateKeyPair()
		val subject = X500Name("CN=$commonName")
		val now = Clock.System.now()
		val holder = JcaX509v3CertificateBuilder(
			subject,
			BigInteger.valueOf(serial),
			Date(now.minus(1.days).toEpochMilliseconds()),
			Date(now.plus(365.days).toEpochMilliseconds()),
			subject,
			keyPair.public,
		).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
		return JcaX509CertificateConverter().getCertificate(holder)
	}

	/** The line the probe worker prints for the token labelled [label] with [serial] in [slot]. */
	fun identityLine(label: String, serial: String, slot: Long) = "$label\t$serial\t$slot"

	/** The `CERT` line the probe worker prints for [certificate] found in [slot]. */
	fun certLine(slot: Long, certificate: X509Certificate) =
		"CERT\t$slot\t01\t${Base64.encode("label".encodeToByteArray())}\t${Base64.encode(certificate.encoded)}"

	/** The output of a `--certs` probe run that printed [lines]. */
	fun probeOutput(vararg lines: String) = (lines.toList() + Pkcs11Prober.OUTPUT_TERMINATOR).joinToString("\n")

	/** A prober whose `--certs` run of the library prints [stdout], with the real identity parser. */
	fun prober(stdout: String): Pkcs11Prober {
		val parser = Pkcs11SubprocessProber()
		return mockk<Pkcs11Prober> {
			every { runCertProbe(libraryPath, any()) } returns Pkcs11SubprocessResult.Success(pid = 1L, stdout = stdout)
			every { parseIdentities(any(), any()) } answers { parser.parseIdentities(firstArg(), secondArg()) }
		}
	}

	/**
	 * A token service that probes with [prober] within [probeTimeout] and has no password dialog
	 * to ask.
	 */
	fun service(prober: Pkcs11Prober, probeTimeout: Pkcs11ProbeTimeout = Pkcs11ProbeTimeout()): DssTokenService {
		val discoverer = mockk<Pkcs11Discoverer> {
			every { discoveryRunning } returns MutableStateFlow(false)
		}
		return DssTokenService(
			mockk<PasswordCallback>(),
			pkcs11Discoverer = discoverer,
			prober = prober,
			probeTimeout = probeTimeout,
		)
	}

	/** The tokens discovery builds from the identity lines of [stdout]. */
	fun discovered(prober: Pkcs11Prober, stdout: String): List<TokenInfo> =
		Pkcs11TokenInfoDeduplicator().buildTokenInfoList(
			listOf(Triple("Middleware", libraryPath, prober.parseIdentities(stdout, libraryPath)))
		)

	/** A token of the library whose id was built from [serial], pinned to [slot]. */
	fun card(serial: String, slot: Long) = TokenInfo(
		id = pkcs11TokenId(serial),
		name = "Card",
		type = TokenType.PKCS11,
		path = libraryPath,
		requiresPin = true,
		pkcs11SlotId = slot,
	)

	test("a token lists the certificates of every slot that holds it and none of another token's slot") {
		val firstOfA = certificate("Card A First", 1)
		val ofB = certificate("Card B", 2)
		val secondOfA = certificate("Card A Second", 3)
		val stdout = probeOutput(
			identityLine("Card A", "SN-A", 1),
			identityLine("Card B", "SN-B", 2),
			identityLine("Card A", "SN-A", 3),
			certLine(1, firstOfA),
			certLine(2, ofB),
			certLine(3, secondOfA),
		)
		val prober = prober(stdout)
		val service = service(prober)
		val tokens = discovered(prober, stdout)
		tokens shouldHaveSize 2
		val (cardA, cardB) = tokens

		val listedA = service.listCertificatesNoLogin(cardA).shouldBeRight()
		val listedB = service.listCertificatesNoLogin(cardB).shouldBeRight()

		listedA.map { it.subjectDN to it.pkcs11SlotId } shouldContainExactly listOf(
			"CN=Card A First" to 1L,
			"CN=Card A Second" to 3L,
		)
		listedA.map { it.alias } shouldContainExactly listOf(
			pkcs11CertAlias(firstOfA, cardA),
			pkcs11CertAlias(secondOfA, cardA),
		)
		listedA.map { it.tokenInfo }.distinct() shouldContainExactly listOf(cardA)
		listedB.map { it.subjectDN to it.pkcs11SlotId } shouldContainExactly listOf("CN=Card B" to 2L)
		listedB.map { it.alias } shouldContainExactly listOf(pkcs11CertAlias(ofB, cardB))
	}

	test("a token matches its slots when the probe run pads and cases its serial differently") {
		val own = certificate("Own", 1)
		val other = certificate("Other", 2)
		val stdout = probeOutput(
			identityLine("Card", "0123ABCD  $nul$nul", 4),
			identityLine("Other Card", "0123ABCE", 5),
			certLine(4, own),
			certLine(5, other),
		)
		val service = service(prober(stdout))

		val listed = service.listCertificatesNoLogin(card("0123abcd", 4)).shouldBeRight()

		listed.map { it.subjectDN to it.pkcs11SlotId } shouldContainExactly listOf("CN=Own" to 4L)
	}

	test("a token that no slot of the probe run holds lists nothing") {
		val stdout = probeOutput(
			identityLine("Card A", "SN-A", 1),
			certLine(1, certificate("Card A", 1)),
		)
		val service = service(prober(stdout))

		service.listCertificatesNoLogin(card("SN-GONE", 1)).shouldBeRight().shouldBeEmpty()
	}

	test("a token lists nothing from the slot of a token whose serial begins or ends with its own") {
		val stdout = probeOutput(
			identityLine("Card", "0123", 1),
			identityLine("Longer Card", "01234", 2),
			identityLine("Prefixed Card", "90123", 3),
			certLine(1, certificate("Own", 1)),
			certLine(2, certificate("Longer", 2)),
			certLine(3, certificate("Prefixed", 3)),
		)
		val service = service(prober(stdout))

		val listed = service.listCertificatesNoLogin(card("0123", 1)).shouldBeRight()

		listed.map { it.subjectDN to it.pkcs11SlotId } shouldContainExactly listOf("CN=Own" to 1L)
	}

	test("a certificate from a slot the probe run names no token for is not listed") {
		val stdout = probeOutput(
			identityLine("Card A", "SN-A", 1),
			certLine(1, certificate("Card A", 1)),
			certLine(6, certificate("Unnamed", 2)),
		)
		val service = service(prober(stdout))

		val listed = service.listCertificatesNoLogin(card("SN-A", 1)).shouldBeRight()

		listed.map { it.subjectDN to it.pkcs11SlotId } shouldContainExactly listOf("CN=Card A" to 1L)
	}

	test("a token matches its slots whatever order the probe run prints its lines in") {
		val stdout = probeOutput(
			certLine(1, certificate("Card A", 1)),
			certLine(2, certificate("Card B", 2)),
			identityLine("Card B", "SN-B", 2),
			identityLine("Card A", "SN-A", 1),
		)
		val service = service(prober(stdout))

		val listed = service.listCertificatesNoLogin(card("SN-A", 1)).shouldBeRight()

		listed.map { it.subjectDN to it.pkcs11SlotId } shouldContainExactly listOf("CN=Card A" to 1L)
	}

	test("a PKCS#11 token whose serial is blank lists nothing, without running the probe") {
		val stdout = probeOutput(
			identityLine("Card A", "", 1),
			identityLine("Card B", " $nul$nul", 2),
			certLine(1, certificate("Card A", 1)),
			certLine(2, certificate("Card B", 2)),
		)
		val prober = prober(stdout)
		val service = service(prober)

		service.listCertificatesNoLogin(card("", 1)).shouldBeRight().shouldBeEmpty()
		service.listCertificatesNoLogin(card(" $nul$nul", 2)).shouldBeRight().shouldBeEmpty()

		verify(exactly = 0) { prober.runCertProbe(any(), any()) }
	}

	test("a PKCS#11 token whose id carries no serial lists nothing, without running the probe") {
		val stdout = probeOutput(
			identityLine("Card A", "SN-A", 1),
			certLine(1, certificate("Card A", 1)),
		)
		val prober = prober(stdout)
		val service = service(prober)

		service.listCertificatesNoLogin(card("SN-A", 1).copy(id = "card-SN-A")).shouldBeRight().shouldBeEmpty()

		verify(exactly = 0) { prober.runCertProbe(any(), any()) }
	}

	test("the probe is killed after the probe timeout configured when the listing runs") {
		val stdout = probeOutput(
			identityLine("Card A", "SN-A", 1),
			certLine(1, certificate("Card A", 1)),
		)
		val prober = prober(stdout)
		val probeTimeout = Pkcs11ProbeTimeout(initialSeconds = 7)
		val service = service(prober, probeTimeout)
		probeTimeout.update(90)

		service.listCertificatesNoLogin(card("SN-A", 1)).shouldBeRight() shouldHaveSize 1

		verify(exactly = 1) { prober.runCertProbe(libraryPath, 90) }
	}
})
