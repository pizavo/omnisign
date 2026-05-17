package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Verifies [Pkcs11CandidateCollector] filename heuristics, vendor-name derivation, proxy-path
 * recognition, the OS-source fan-out, and the candidate-enumeration cache.
 *
 * The PC/SC + Calais branch ([Pkcs11PcscCalaisResolver]) is Windows-only and not exercised
 * here; these tests cover the OS-agnostic surface and the Linux/macOS p11-kit-proxy branch.
 */
class Pkcs11CandidateCollectorTest : FunSpec({

	fun collector() = Pkcs11CandidateCollector()

	test("isPkcs11FileName matches known PKCS11 naming patterns") {
		val c = collector()
		c.isPkcs11FileName("eTPKCS11.dll").shouldBeTrue()
		c.isPkcs11FileName("opensc-pkcs11.dll").shouldBeTrue()
		c.isPkcs11FileName("libsofthsm2.so").shouldBeTrue()
		c.isPkcs11FileName("iidp11.dll").shouldBeTrue()
		c.isPkcs11FileName("cmP11.dll").shouldBeTrue()
		c.isPkcs11FileName("libcryptoki.so").shouldBeTrue()
		c.isPkcs11FileName("libp11.so").shouldBeTrue()
		c.isPkcs11FileName("p11-kit.so").shouldBeTrue()
		c.isPkcs11FileName("p11.dll").shouldBeTrue()
	}

	test("isPkcs11FileName rejects unrelated DLLs") {
		val c = collector()
		c.isPkcs11FileName("kernel32.dll").shouldBeFalse()
		c.isPkcs11FileName("ntdll.dll").shouldBeFalse()
		c.isPkcs11FileName("user32.dll").shouldBeFalse()
		c.isPkcs11FileName("libssl.so").shouldBeFalse()
	}

	test("isPkcs11FileName rejects Visual C++ runtime DLLs that contain p11 as a version fragment") {
		val c = collector()
		c.isPkcs11FileName("msvcp110.dll").shouldBeFalse()
		c.isPkcs11FileName("msvcp110_win.dll").shouldBeFalse()
		c.isPkcs11FileName("vcamp110.dll").shouldBeFalse()
		c.isPkcs11FileName("vcomp110.dll").shouldBeFalse()
		c.isPkcs11FileName("msvcp110d.dll").shouldBeFalse()
	}

	test("isPkcs11FileName rejects PKCS11 spy and debugging wrapper libraries") {
		val c = collector()
		c.isPkcs11FileName("pkcs11-spy.so").shouldBeFalse()
		c.isPkcs11FileName("pkcs11-spy.dll").shouldBeFalse()
		c.isPkcs11FileName("pkcs11spy.so").shouldBeFalse()
		c.isPkcs11FileName("PKCS11-SPY.DLL").shouldBeFalse()
		c.isPkcs11FileName("p11-spy.so").shouldBeFalse()
		c.isPkcs11FileName("p11spy.dll").shouldBeFalse()
	}

	test("isPkcs11FileName matches YubiKey YKCS11 library") {
		collector().isPkcs11FileName("libykcs11.so").shouldBeTrue()
		collector().isPkcs11FileName("libykcs11.dylib").shouldBeTrue()
	}

	test("isPkcs11FileName matches SafeNet eTPkcs11 library") {
		collector().isPkcs11FileName("libeTPkcs11.so").shouldBeTrue()
		collector().isPkcs11FileName("eTPKCS11.dll").shouldBeTrue()
	}

	test("isSpyLibrary recognises known spy library filenames") {
		val c = collector()
		c.isSpyLibrary("pkcs11-spy.so").shouldBeTrue()
		c.isSpyLibrary("pkcs11-spy.dll").shouldBeTrue()
		c.isSpyLibrary("pkcs11spy.so").shouldBeTrue()
		c.isSpyLibrary("p11-spy.so").shouldBeTrue()
		c.isSpyLibrary("p11spy.dll").shouldBeTrue()
	}

	test("isSpyLibrary rejects real PKCS11 middleware") {
		val c = collector()
		c.isSpyLibrary("opensc-pkcs11.so").shouldBeFalse()
		c.isSpyLibrary("eTPKCS11.dll").shouldBeFalse()
		c.isSpyLibrary("libsofthsm2.so").shouldBeFalse()
	}

	test("deriveMiddlewareName identifies SafeNet eToken paths") {
		val c = collector()
		c.deriveMiddlewareName("C:\\Windows\\System32\\eTPKCS11.dll") shouldBe "SafeNet eToken"
		c.deriveMiddlewareName("/usr/lib/libeTPkcs11.so") shouldBe "SafeNet eToken"
	}

	test("deriveMiddlewareName identifies Gemalto IDPrime paths") {
		val c = collector()
		c.deriveMiddlewareName("C:\\Windows\\System32\\gclib.dll") shouldBe "Thales/Gemalto IDPrime"
		c.deriveMiddlewareName("/usr/lib/libgclib.so") shouldBe "Thales/Gemalto IDPrime"
	}

	test("deriveMiddlewareName identifies OpenSC paths") {
		val c = collector()
		c.deriveMiddlewareName("/usr/lib/opensc-pkcs11.so") shouldBe "OpenSC"
		c.deriveMiddlewareName("C:\\Windows\\System32\\opensc-pkcs11.dll") shouldBe "OpenSC"
	}

	test("deriveMiddlewareName identifies YubiKey YKCS11 paths") {
		val c = collector()
		c.deriveMiddlewareName("/usr/lib/libykcs11.so") shouldBe "YubiKey (YKCS11)"
		c.deriveMiddlewareName("/usr/local/lib/libykcs11.dylib") shouldBe "YubiKey (YKCS11)"
	}

	test("deriveMiddlewareName falls back to filename for unknown libraries") {
		collector().deriveMiddlewareName("C:\\Some\\Path\\acme-token.dll") shouldBe "acme-token.dll"
	}

	test("discoverViaOs returns empty list without throwing on unknown OS") {
		runCatching { collector().discoverViaOs(os = "haiku", jvmIs64Bit = true) }
			.isSuccess.shouldBeTrue()
	}

	test("discoverViaP11KitProxy returns empty list when no proxy path exists") {
		val result = collector().discoverViaP11KitProxy(proxyPaths = listOf("/tmp/nonexistent-p11-proxy.so"))
		result.shouldBeEmpty()
	}

	test("discoverViaP11KitProxy returns first existing proxy path") {
		val proxyFile = File.createTempFile("p11-kit-proxy", ".so").also { it.deleteOnExit() }

		val result = collector().discoverViaP11KitProxy(
			proxyPaths = listOf("/tmp/nonexistent.so", proxyFile.absolutePath),
		)

		result.shouldHaveSize(1)
		result.first().first shouldBe "p11-kit Proxy"
		result.first().second shouldBe proxyFile.absolutePath
	}

	test("collectCandidates caches its result so a second call returns the same instance") {
		val lib = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val c = collector()

		val first = c.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))
		val second = c.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))

		(first === second) shouldBe true
	}

	test("invalidateCandidates forces collectCandidates to re-enumerate") {
		val lib = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val c = collector()

		val first = c.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))
		c.invalidateCandidates()
		val second = c.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))

		(first === second) shouldBe false
		first shouldBe second
	}

	test("collectCandidates uses distinct cache entries for different user-library inputs") {
		val lib1 = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val lib2 = File.createTempFile("opensc", ".dll").also { it.deleteOnExit() }
		val c = collector()

		val first = c.collectCandidates(userPkcs11Libraries = listOf("a" to lib1.absolutePath))
		val second = c.collectCandidates(userPkcs11Libraries = listOf("b" to lib2.absolutePath))

		(first === second) shouldBe false
	}
})
