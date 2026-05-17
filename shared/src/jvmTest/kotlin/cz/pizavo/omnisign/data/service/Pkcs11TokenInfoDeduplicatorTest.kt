package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Verifies [Pkcs11TokenInfoDeduplicator]: the serial-based collapse, the
 * direct-before-proxy ordering, the "identities required" rule, proxy-path recognition,
 * and serial normalisation.
 */
class Pkcs11TokenInfoDeduplicatorTest : FunSpec({

	// The PKCS#11 NUL padding character, expressed without a unicode-escape source literal.
	val nul = Char(0)

	fun id(label: String, serial: String, path: String) =
		Pkcs11TokenIdentity(label = label, serialNumber = serial, libraryPath = path)

	test("isProxyPath recognises p11-kit-proxy paths") {
		val d = Pkcs11TokenInfoDeduplicator()
		d.isProxyPath("/usr/lib/x86_64-linux-gnu/pkcs11/p11-kit-proxy.so").shouldBeTrue()
		d.isProxyPath("/usr/lib64/pkcs11/p11-kit-proxy.so").shouldBeTrue()
	}

	test("isProxyPath rejects non-proxy paths") {
		val d = Pkcs11TokenInfoDeduplicator()
		d.isProxyPath("/usr/lib/libeTPkcs11.so").shouldBeFalse()
		d.isProxyPath("/usr/lib/opensc-pkcs11.so").shouldBeFalse()
	}

	test("normalizeSerial strips whitespace, null bytes, and uppercases") {
		normalizeSerial("abc 123$nul$nul") shouldBe "ABC123"
	}

	test("normalizeSerial produces identical output for space-padded and null-padded serials") {
		normalizeSerial("SN-42$nul$nul$nul") shouldBe normalizeSerial("SN-42" + " ".repeat(3))
	}

	test("normalizeSerial handles already-clean serial unchanged except for case") {
		normalizeSerial("ABC123") shouldBe "ABC123"
		normalizeSerial("abc123") shouldBe "ABC123"
	}

	test("buildTokenInfoList drops libraries that returned no identities") {
		val d = Pkcs11TokenInfoDeduplicator()

		val tokens = d.buildTokenInfoList(
			listOf(
				Triple("Empty Lib", "/empty.so", emptyList()),
				Triple("Real Lib", "/real.so", listOf(id("Token", "SN1", "/real.so"))),
			)
		)

		tokens.shouldHaveSize(1)
		tokens.first().id shouldBe "pkcs11-SN1"
		tokens.first().path shouldBe "/real.so"
	}

	test("buildTokenInfoList collapses the same serial reported by multiple libraries") {
		val d = Pkcs11TokenInfoDeduplicator()

		val tokens = d.buildTokenInfoList(
			listOf(
				Triple("Lib A", "/a.so", listOf(id("Token", "DUP", "/a.so"))),
				Triple("Lib B", "/b.so", listOf(id("Token", "DUP", "/b.so"))),
			)
		)

		tokens.shouldHaveSize(1)
	}

	test("buildTokenInfoList prefers a direct path over the p11-kit proxy for the same serial") {
		val d = Pkcs11TokenInfoDeduplicator()

		val tokens = d.buildTokenInfoList(
			listOf(
				Triple("p11-kit Proxy", "/usr/lib/pkcs11/p11-kit-proxy.so", listOf(id("T", "SHARED", "/usr/lib/pkcs11/p11-kit-proxy.so"))),
				Triple("SafeNet eToken", "/usr/lib/libeTPkcs11.so", listOf(id("T", "SHARED", "/usr/lib/libeTPkcs11.so"))),
			)
		)

		tokens.shouldHaveSize(1)
		tokens.first().path shouldBe "/usr/lib/libeTPkcs11.so"
	}

	test("buildTokenInfoList produces separate entries for distinct serials") {
		val d = Pkcs11TokenInfoDeduplicator()

		val tokens = d.buildTokenInfoList(
			listOf(
				Triple("Lib", "/lib.so", listOf(id("A", "SN-1", "/lib.so"), id("B", "SN-2", "/lib.so"))),
			)
		)

		tokens.shouldHaveSize(2)
		tokens.map { it.name }.toSet() shouldBe setOf("A", "B")
	}

	test("buildTokenInfoList returns empty for no candidates") {
		Pkcs11TokenInfoDeduplicator().buildTokenInfoList(emptyList()).shouldBeEmpty()
	}
})
