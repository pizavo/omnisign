package cz.pizavo.omnisign.data.trust

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import kotlin.io.path.exists

/**
 * Verifies [FileTrustStore]: content-addressed storage, per-scope references, per-reference type,
 * dedup, reference-counted GC, scope resolution, and load-time repair.
 */
class FileTrustStoreTest : FunSpec({

	fun selfSigned(cn: String): X509Certificate {
		val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		val name = X500Name("CN=$cn,O=OmniSign Test")
		val now = System.currentTimeMillis()
		val builder = JcaX509v3CertificateBuilder(
			name,
			BigInteger.valueOf(now),
			Date(now - 1000L),
			Date(now + 365L * 24 * 60 * 60 * 1000),
			name,
			keyPair.public,
		)
		val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
		return JcaX509CertificateConverter().getCertificate(builder.build(signer))
	}

	fun der(cn: String): ByteArray = selfSigned(cn).encoded

	fun pem(der: ByteArray): ByteArray {
		val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
		return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n".toByteArray()
	}

	fun newStore() = FileTrustStore(Files.createTempDirectory("truststore-test"))

	test("add stores a content-addressed file and lists it") {
		val store = newStore()
		val added = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY).shouldBeRight()
		added.fingerprint shouldStartWith "sha256-"
		added.type shouldBe TrustedCertificateType.ANY
		store.list(TrustScope.Global).shouldBeRight().map { it.fingerprint } shouldBe listOf(added.fingerprint)
	}

	test("fingerprint is the algorithm-prefixed SHA-256 of the DER") {
		val store = newStore()
		val bytes = der("Root")
		val added = store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()
		val expected = "sha256-" + MessageDigest.getInstance("SHA-256").digest(bytes)
			.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
		added.fingerprint shouldBe expected
	}

	test("the same cert in two scopes is stored once and listed in each") {
		val store = newStore()
		val bytes = der("Shared")
		store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()
		val profile = TrustScope.Profile("p")
		store.add(profile, bytes, TrustedCertificateType.TSA).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 1
		store.list(profile).shouldBeRight() shouldHaveSize 1
		store.resolve(profile).shouldBeRight() shouldHaveSize 1
	}

	test("PEM and DER of the same cert deduplicate") {
		val store = newStore()
		val bytes = der("Root")
		val a = store.add(TrustScope.Global, bytes, TrustedCertificateType.ANY).shouldBeRight()
		val b = store.add(TrustScope.Global, pem(bytes), TrustedCertificateType.ANY).shouldBeRight()
		b.fingerprint shouldBe a.fingerprint
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 1
	}

	test("type is per reference: CA in global, TSA in a profile") {
		val store = newStore()
		val bytes = der("Shared")
		store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()
		val profile = TrustScope.Profile("p")
		store.add(profile, bytes, TrustedCertificateType.TSA).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight().single().type shouldBe TrustedCertificateType.CA
		store.list(profile).shouldBeRight().single().type shouldBe TrustedCertificateType.TSA
	}

	test("resolve unions global and the active scope, with the scope type overriding") {
		val store = newStore()
		val shared = der("Shared")
		store.add(TrustScope.Global, shared, TrustedCertificateType.CA).shouldBeRight()
		store.add(TrustScope.Global, der("GlobalOnly"), TrustedCertificateType.ANY).shouldBeRight()
		val profile = TrustScope.Profile("p")
		val sharedFp = store.add(profile, shared, TrustedCertificateType.TSA).shouldBeRight().fingerprint
		val anchors = store.resolve(profile).shouldBeRight()
		anchors shouldHaveSize 2
		anchors.single { it.fingerprint == sharedFp }.type shouldBe TrustedCertificateType.TSA
	}

	test("remove drops the reference and GCs the file when the last reference goes") {
		val dir = Files.createTempDirectory("truststore-test")
		val store = FileTrustStore(dir)
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY).shouldBeRight().fingerprint
		store.remove(TrustScope.Global, fp).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
		dir.resolve("$fp.der").exists() shouldBe false
	}

	test("remove keeps the file while another scope still references it") {
		val dir = Files.createTempDirectory("truststore-test")
		val store = FileTrustStore(dir)
		val bytes = der("Shared")
		val fp = store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		val profile = TrustScope.Profile("p")
		store.add(profile, bytes, TrustedCertificateType.TSA).shouldBeRight()
		store.remove(TrustScope.Global, fp).shouldBeRight()
		dir.resolve("$fp.der").exists() shouldBe true
		store.list(profile).shouldBeRight() shouldHaveSize 1
	}

	test("clearProfileScope removes the scope and GCs solely-referenced certs") {
		val dir = Files.createTempDirectory("truststore-test")
		val store = FileTrustStore(dir)
		val profile = TrustScope.Profile("p")
		val fp = store.add(profile, der("ProfileOnly"), TrustedCertificateType.ANY).shouldBeRight().fingerprint
		store.clearProfileScope("p").shouldBeRight()
		store.list(profile).shouldBeRight() shouldHaveSize 0
		dir.resolve("$fp.der").exists() shouldBe false
	}

	test("setType changes the per-scope type") {
		val store = newStore()
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY).shouldBeRight().fingerprint
		store.setType(TrustScope.Global, fp, TrustedCertificateType.TSA).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight().single().type shouldBe TrustedCertificateType.TSA
	}

	test("a fresh store over the same directory repairs a dangling reference") {
		val dir = Files.createTempDirectory("truststore-test")
		val fp = FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY)
			.shouldBeRight().fingerprint
		Files.delete(dir.resolve("$fp.der"))
		FileTrustStore(dir).list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
	}

	test("removing an unknown fingerprint returns NotFound") {
		val store = newStore()
		store.remove(TrustScope.Global, "sha256-deadbeef").shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.NotFound>()
	}

	test("reference adds an already-stored cert to another scope without its bytes") {
		val store = newStore()
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight().fingerprint
		store.reference(TrustScope.Profile("p"), fp, TrustedCertificateType.TSA).shouldBeRight()
		val referenced = store.list(TrustScope.Profile("p")).shouldBeRight().single()
		referenced.fingerprint shouldBe fp
		referenced.type shouldBe TrustedCertificateType.TSA
	}

	test("reference of an unknown fingerprint returns NotFound") {
		val store = newStore()
		store.reference(TrustScope.Global, "sha256-unknown", TrustedCertificateType.ANY).shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.NotFound>()
	}

	test("findBySource resolves the fingerprint recorded for a source, and null otherwise") {
		val store = newStore()
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.CA, source = "certs/root.pem")
			.shouldBeRight().fingerprint
		store.findBySource("certs/root.pem").shouldBeRight() shouldBe fp
		store.findBySource("certs/absent.pem").shouldBeRight() shouldBe null
	}

	test("scopes lists every non-empty scope") {
		val store = newStore()
		store.add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight()
		store.add(TrustScope.Profile("p1"), der("P1"), TrustedCertificateType.ANY).shouldBeRight()
		store.scopes().shouldBeRight() shouldBe setOf(TrustScope.Global, TrustScope.Profile("p1"))
	}

	test("inspect parses a certificate without storing it") {
		val store = newStore()
		val parsed = store.inspect(der("Inspector")).shouldBeRight()
		parsed.fingerprint shouldStartWith "sha256-"
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
	}

	test("inspect returns ParseFailed on non-certificate bytes") {
		val store = newStore()
		store.inspect(byteArrayOf(1, 2, 3)).shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.ParseFailed>()
	}
})
