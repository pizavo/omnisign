package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.io.File

/**
 * Verifies [Pkcs11Discoverer] bitness-aware candidate selection, filename heuristics,
 * vendor name derivation, deduplication logic, and hardware identity-based token merging.
 */
class Pkcs11DiscovererTest : FunSpec({
	
	val noProbe: (String) -> List<Pkcs11TokenIdentity> = { emptyList() }

	fun discoverer() = Pkcs11Discoverer(tokenProber = noProbe)
	
	test("64-bit JVM on Windows fallback list contains only Program Files paths, not System32") {
		val paths = discoverer().candidatesForOs("windows 10", jvmIs64Bit = true).map { it.second }
		
		paths.none { it.contains("System32") || it.contains("SysWOW64") || it.contains("(x86)") }.shouldBeTrue()
		paths.all { it.contains("Program Files") || it.contains("SoftHSM2") }.shouldBeTrue()
	}
	
	test("32-bit JVM on Windows fallback list contains only Program Files x86 paths") {
		val paths = discoverer().candidatesForOs("windows 10", jvmIs64Bit = false).map { it.second }
		
		paths.none { it.contains("System32") || it.contains("SysWOW64") }.shouldBeTrue()
		paths.all { it.contains("(x86)") }.shouldBeTrue()
	}
	
	test("64-bit and 32-bit Windows candidate sets are disjoint") {
		val d = discoverer()
		val paths64 = d.candidatesForOs("windows 10", jvmIs64Bit = true).map { it.second }.toSet()
		val paths32 = d.candidatesForOs("windows 10", jvmIs64Bit = false).map { it.second }.toSet()
		
		(paths64 intersect paths32).shouldBeEmpty()
	}
	
	test("64-bit JVM on Linux picks lib64 and multiarch paths") {
		discoverer().candidatesForOs("linux", jvmIs64Bit = true).map { it.second }
			.any { it.contains("lib64") || it.contains("x86_64") || it.contains("aarch64") }
			.shouldBeTrue()
	}
	
	test("32-bit JVM on Linux does not list lib64 or multiarch paths") {
		discoverer().candidatesForOs("linux", jvmIs64Bit = false).map { it.second }
			.none { it.contains("lib64") || it.contains("x86_64") || it.contains("aarch64") }
			.shouldBeTrue()
	}
	
	test("isPkcs11FileName matches known PKCS11 naming patterns") {
		val d = discoverer()
		d.isPkcs11FileName("eTPKCS11.dll").shouldBeTrue()
		d.isPkcs11FileName("opensc-pkcs11.dll").shouldBeTrue()
		d.isPkcs11FileName("libsofthsm2.so").shouldBeTrue()
		d.isPkcs11FileName("iidp11.dll").shouldBeTrue()
		d.isPkcs11FileName("cmP11.dll").shouldBeTrue()
		d.isPkcs11FileName("libcryptoki.so").shouldBeTrue()
		d.isPkcs11FileName("libp11.so").shouldBeTrue()
		d.isPkcs11FileName("p11-kit.so").shouldBeTrue()
		d.isPkcs11FileName("p11.dll").shouldBeTrue()
	}

	test("isPkcs11FileName rejects unrelated DLLs") {
		val d = discoverer()
		d.isPkcs11FileName("kernel32.dll").shouldBeFalse()
		d.isPkcs11FileName("ntdll.dll").shouldBeFalse()
		d.isPkcs11FileName("user32.dll").shouldBeFalse()
		d.isPkcs11FileName("libssl.so").shouldBeFalse()
	}

	test("isPkcs11FileName rejects Visual C++ runtime DLLs that contain p11 as a version fragment") {
		val d = discoverer()
		d.isPkcs11FileName("msvcp110.dll").shouldBeFalse()
		d.isPkcs11FileName("msvcp110_win.dll").shouldBeFalse()
		d.isPkcs11FileName("vcamp110.dll").shouldBeFalse()
		d.isPkcs11FileName("vcomp110.dll").shouldBeFalse()
		d.isPkcs11FileName("msvcp110d.dll").shouldBeFalse()
	}
 
	test("isPkcs11FileName rejects PKCS11 spy and debugging wrapper libraries") {
		val d = discoverer()
		d.isPkcs11FileName("pkcs11-spy.so").shouldBeFalse()
		d.isPkcs11FileName("pkcs11-spy.dll").shouldBeFalse()
		d.isPkcs11FileName("pkcs11spy.so").shouldBeFalse()
		d.isPkcs11FileName("PKCS11-SPY.DLL").shouldBeFalse()
		d.isPkcs11FileName("p11-spy.so").shouldBeFalse()
		d.isPkcs11FileName("p11spy.dll").shouldBeFalse()
	}

	test("isSpyLibrary recognises known spy library filenames") {
		val d = discoverer()
		d.isSpyLibrary("pkcs11-spy.so").shouldBeTrue()
		d.isSpyLibrary("pkcs11-spy.dll").shouldBeTrue()
		d.isSpyLibrary("pkcs11spy.so").shouldBeTrue()
		d.isSpyLibrary("p11-spy.so").shouldBeTrue()
		d.isSpyLibrary("p11spy.dll").shouldBeTrue()
	}

	test("isSpyLibrary rejects real PKCS11 middleware") {
		val d = discoverer()
		d.isSpyLibrary("opensc-pkcs11.so").shouldBeFalse()
		d.isSpyLibrary("eTPKCS11.dll").shouldBeFalse()
		d.isSpyLibrary("libsofthsm2.so").shouldBeFalse()
	}

	test("discoverTokens skips spy libraries found in lib directories") {
		val dropDir = File.createTempFile("pkcs11-drop", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
		val spyFile = File(dropDir, "pkcs11-spy.so").also { it.createNewFile(); it.deleteOnExit() }
		val realFile = File(dropDir, "vendor-pkcs11.so").also { it.createNewFile(); it.deleteOnExit() }

		val tokens = discoverer().discoverTokens(appDataPkcs11Dir = dropDir)

		tokens.any { it.path == spyFile.absolutePath }.shouldBeFalse()
		tokens.any { it.path == realFile.absolutePath }.shouldBeTrue()

		dropDir.deleteRecursively()
	}

	test("discoverTokens skips spy libraries from user-supplied paths") {
		val spyFile = File.createTempFile("pkcs11-spy", ".so").also { it.deleteOnExit() }

		val tokens = discoverer().discoverTokens(
			userPkcs11Libraries = listOf("Spy Module" to spyFile.absolutePath),
		)

		tokens.any { it.path == spyFile.absolutePath }.shouldBeFalse()
	}
	
	test("deriveMiddlewareName identifies SafeNet eToken paths") {
		val d = discoverer()
		d.deriveMiddlewareName("C:\\Windows\\System32\\eTPKCS11.dll") shouldBe "SafeNet eToken"
		d.deriveMiddlewareName("/usr/lib/libeTPkcs11.so") shouldBe "SafeNet eToken"
	}
	
	test("deriveMiddlewareName identifies Gemalto IDPrime paths") {
		val d = discoverer()
		d.deriveMiddlewareName("C:\\Windows\\System32\\gclib.dll") shouldBe "Thales/Gemalto IDPrime"
		d.deriveMiddlewareName("/usr/lib/libgclib.so") shouldBe "Thales/Gemalto IDPrime"
	}
	
	test("deriveMiddlewareName identifies OpenSC paths") {
		val d = discoverer()
		d.deriveMiddlewareName("/usr/lib/opensc-pkcs11.so") shouldBe "OpenSC"
		d.deriveMiddlewareName("C:\\Windows\\System32\\opensc-pkcs11.dll") shouldBe "OpenSC"
	}
	
	test("deriveMiddlewareName falls back to filename for unknown libraries") {
		discoverer().deriveMiddlewareName("C:\\Some\\Path\\acme-token.dll") shouldBe "acme-token.dll"
	}
	
	test("discoverTokens deduplicates same canonical path from multiple sources") {
		val tmpFile = File.createTempFile("acmevendor-pkcs11", ".so").also { it.deleteOnExit() }
		
		discoverer().discoverTokens(
			userPkcs11Libraries = listOf(
				"Acme (source 1)" to tmpFile.absolutePath,
				"Acme (source 2)" to tmpFile.absolutePath,
			)
		).filter { it.path == tmpFile.absolutePath }.shouldHaveSize(1)
	}
	
	test("discoverTokens includes user-supplied library when file exists") {
		val tmpFile = File.createTempFile("custom-pkcs11", ".so").also { it.deleteOnExit() }
		
		discoverer().discoverTokens(
			userPkcs11Libraries = listOf("My Custom Token" to tmpFile.absolutePath)
		).any { it.path == tmpFile.absolutePath }.shouldBeTrue()
	}
	
	test("discoverTokens ignores user-supplied library when file does not exist") {
		discoverer().discoverTokens(
			userPkcs11Libraries = listOf("Ghost Token" to "/tmp/does-not-exist-pkcs11.so")
		).any { it.path == "/tmp/does-not-exist-pkcs11.so" }.shouldBeFalse()
	}
	
	test("discoverViaOs returns empty list without throwing on unknown OS") {
		runCatching { discoverer().discoverViaOs(os = "haiku", jvmIs64Bit = true) }
			.isSuccess.shouldBeTrue()
	}
	
	test("discoverTokens picks up PKCS11-named files from app-data drop directory") {
		val dropDir = File.createTempFile("pkcs11-drop", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
		val libFile = File(dropDir, "vendor-pkcs11.so").also { it.createNewFile(); it.deleteOnExit() }
		
		discoverer().discoverTokens(appDataPkcs11Dir = dropDir)
			.any { it.path == libFile.absolutePath }.shouldBeTrue()
		
		dropDir.deleteRecursively()
	}
	
	test("discoverTokens ignores non-PKCS11-named files in drop directory") {
		val dropDir = File.createTempFile("pkcs11-drop", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
		val unrelated = File(dropDir, "readme.txt").also { it.createNewFile(); it.deleteOnExit() }
		
		discoverer().discoverTokens(appDataPkcs11Dir = dropDir)
			.any { it.path == unrelated.absolutePath }.shouldBeFalse()
		
		dropDir.deleteRecursively()
	}

	test("deriveMiddlewareFamily groups SafeNet and Gemalto libraries into the same family") {
		val d = discoverer()
		d.deriveMiddlewareFamily("C:\\Windows\\System32\\eTPKCS11.dll") shouldBe "safenet"
		d.deriveMiddlewareFamily("C:\\Windows\\System32\\gclib.dll") shouldBe "safenet"
		d.deriveMiddlewareFamily("C:\\Program Files\\SafeNet\\Authentication\\SAC\\x64\\eTPKCS11.dll") shouldBe "safenet"
	}

	test("deriveMiddlewareFamily assigns distinct families to different vendors") {
		val d = discoverer()
		d.deriveMiddlewareFamily("/usr/lib/opensc-pkcs11.so") shouldBe "opensc"
		d.deriveMiddlewareFamily("/usr/lib/softhsm/libsofthsm2.so") shouldBe "softhsm"
		d.deriveMiddlewareFamily("/usr/lib/iidp11.so") shouldBe "secmaker"
	}

	test("deriveMiddlewareFamily falls back to canonical path for unknown libraries") {
		val tmpFile = File.createTempFile("acme-pkcs11", ".so").also { it.deleteOnExit() }
		val family = discoverer().deriveMiddlewareFamily(tmpFile.absolutePath)
		family shouldBe tmpFile.canonicalPath
	}

	test("discoverTokens deduplicates same-family libraries when probing returns empty") {
		val f1 = File.createTempFile("cmP11-a", ".dll").also { it.deleteOnExit() }
		val f2 = File.createTempFile("charismathics-b-pkcs11", ".dll").also { it.deleteOnExit() }

		val tokens = discoverer().discoverTokens(
			userPkcs11Libraries = listOf(
				"Charismathics A" to f1.absolutePath,
				"Charismathics B" to f2.absolutePath,
			)
		)

		tokens.filter {
			it.path == f1.absolutePath || it.path == f2.absolutePath
		}.shouldHaveSize(1)
	}

	test("discoverTokens deduplicates by serial number when probing returns identities") {
		val lib1 = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val lib2 = File.createTempFile("gclib", ".dll").also { it.deleteOnExit() }

		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			listOf(
				Pkcs11TokenIdentity(
					label = "My SafeNet Token",
					serialNumber = "ABC123",
					libraryPath = path,
				)
			)
		}

		val tokens = Pkcs11Discoverer(tokenProber = fakeProber).discoverTokens(
			userPkcs11Libraries = listOf(
				"SafeNet eToken" to lib1.absolutePath,
				"Thales/Gemalto IDPrime" to lib2.absolutePath,
			)
		)

		val hwTokens = tokens.filter { it.id == "pkcs11-ABC123" }
		hwTokens.shouldHaveSize(1)
		hwTokens.first().name shouldBe "My SafeNet Token"
	}

	test("discoverTokens deduplicates across identity and family paths for same-family libraries") {
		val lib1 = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val lib2 = File.createTempFile("gclib", ".dll").also { it.deleteOnExit() }

		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path == lib1.absolutePath) {
				listOf(
					Pkcs11TokenIdentity(
						label = "VP-SafeNet",
						serialNumber = "SN-CROSS",
						libraryPath = path,
					)
				)
			} else {
				emptyList()
			}
		}

		val tokens = Pkcs11Discoverer(tokenProber = fakeProber).discoverTokens(
			userPkcs11Libraries = listOf(
				"SafeNet eToken" to lib1.absolutePath,
				"Thales/Gemalto IDPrime" to lib2.absolutePath,
			)
		)

		val safenetTokens = tokens.filter {
			it.id == "pkcs11-SN-CROSS" || it.path == lib2.absolutePath
		}
		safenetTokens.shouldHaveSize(1)
		safenetTokens.first().name shouldBe "VP-SafeNet"
	}

	test("discoverTokens produces separate entries for tokens with different serial numbers") {
		val lib = File.createTempFile("softhsm-pkcs11", ".so").also { it.deleteOnExit() }

		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			listOf(
				Pkcs11TokenIdentity(label = "Token A", serialNumber = "SN-001", libraryPath = path),
				Pkcs11TokenIdentity(label = "Token B", serialNumber = "SN-002", libraryPath = path),
			)
		}

		val tokens = Pkcs11Discoverer(tokenProber = fakeProber).discoverTokens(
			userPkcs11Libraries = listOf("SoftHSM" to lib.absolutePath)
		)

		val hwTokens = tokens.filter { it.id.startsWith("pkcs11-SN-") }
		hwTokens.shouldHaveSize(2)
		hwTokens.map { it.name }.toSet() shouldBe setOf("Token A", "Token B")
	}

	test("isPkcs11FileName matches YubiKey YKCS11 library") {
		discoverer().isPkcs11FileName("libykcs11.so").shouldBeTrue()
		discoverer().isPkcs11FileName("libykcs11.dylib").shouldBeTrue()
	}

	test("isPkcs11FileName matches SafeNet eTPkcs11 library") {
		discoverer().isPkcs11FileName("libeTPkcs11.so").shouldBeTrue()
		discoverer().isPkcs11FileName("eTPKCS11.dll").shouldBeTrue()
	}

	test("deriveMiddlewareName identifies YubiKey YKCS11 paths") {
		val d = discoverer()
		d.deriveMiddlewareName("/usr/lib/libykcs11.so") shouldBe "YubiKey (YKCS11)"
		d.deriveMiddlewareName("/usr/local/lib/libykcs11.dylib") shouldBe "YubiKey (YKCS11)"
	}

	test("deriveMiddlewareFamily assigns 'yubikey' family to YKCS11 libraries") {
		discoverer().deriveMiddlewareFamily("/usr/lib/libykcs11.so") shouldBe "yubikey"
	}

	test("discoverViaP11KitProxy returns empty list when no proxy path exists") {
		val result = discoverer().discoverViaP11KitProxy(proxyPaths = listOf("/tmp/nonexistent-p11-proxy.so"))
		result.shouldBeEmpty()
	}

	test("discoverViaP11KitProxy returns first existing proxy path") {
		val proxyFile = File.createTempFile("p11-kit-proxy", ".so").also { it.deleteOnExit() }

		val result = discoverer().discoverViaP11KitProxy(
			proxyPaths = listOf("/tmp/nonexistent.so", proxyFile.absolutePath),
		)

		result.shouldHaveSize(1)
		result.first().first shouldBe "p11-kit Proxy"
		result.first().second shouldBe proxyFile.absolutePath
	}

	test("discoverViaLibDirs finds PKCS11-named files in given directories") {
		val dir = File.createTempFile("libdir", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
		val lib = File(dir, "libykcs11.so").also { it.createNewFile(); it.deleteOnExit() }

		val result = discoverer().discoverViaLibDirs(listOf(dir.absolutePath))

		result.any { it.second == lib.absolutePath }.shouldBeTrue()
		dir.deleteRecursively()
	}

	test("discoverViaLibDirs skips non-PKCS11 files") {
		val dir = File.createTempFile("libdir", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
		File(dir, "libssl.so").also { it.createNewFile(); it.deleteOnExit() }

		val result = discoverer().discoverViaLibDirs(listOf(dir.absolutePath))

		result.shouldBeEmpty()
		dir.deleteRecursively()
	}

	test("discoverViaLibDirs silently skips non-existent directories") {
		runCatching {
			discoverer().discoverViaLibDirs(listOf("/tmp/dir-that-does-not-exist-omnisign"))
		}.isSuccess.shouldBeTrue()
	}

	test("Linux 64-bit candidate list includes SafeNet and YubiKey paths") {
		val paths = discoverer().candidatesForOs("linux", jvmIs64Bit = true).map { it.second }
		paths.any { it.contains("libeTPkcs11") }.shouldBeTrue()
		paths.any { it.contains("libykcs11") }.shouldBeTrue()
	}

	test("macOS candidate list includes SafeNet and YubiKey paths") {
		val paths = discoverer().candidatesForOs("mac os x", jvmIs64Bit = true).map { it.second }
		paths.any { it.contains("libeTPkcs11") }.shouldBeTrue()
		paths.any { it.contains("libykcs11") }.shouldBeTrue()
	}

	test("discoverTokens uses hardware label as token name instead of middleware name") {
		val lib = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }

		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			listOf(
				Pkcs11TokenIdentity(
					label = "John's eToken 5110",
					serialNumber = "0123456789ABCDEF",
					libraryPath = path,
				)
			)
		}

		val tokens = Pkcs11Discoverer(tokenProber = fakeProber).discoverTokens(
			userPkcs11Libraries = listOf("SafeNet eToken" to lib.absolutePath)
		)

		val hwToken = tokens.first { it.id == "pkcs11-0123456789ABCDEF" }
		hwToken.name shouldBe "John's eToken 5110"
	}

	test("probeLibrary delegates to the configured tokenProber") {
		val expected = listOf(
			Pkcs11TokenIdentity(label = "Test Token", serialNumber = "SN-999", libraryPath = "/test.so")
		)
		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { expected }

		val discoverer = Pkcs11Discoverer(tokenProber = fakeProber)

		discoverer.probeLibrary("/test.so") shouldBe expected
	}

	test("probeLibrary returns empty list when tokenProber returns empty") {
		val discoverer = Pkcs11Discoverer(tokenProber = { emptyList() })

		discoverer.probeLibrary("/nonexistent.so").shouldBeEmpty()
	}

	test("resolveProbeClasspath returns non-blank value from java.class.path in test environment") {
		val classpath = resolveProbeClasspath()

		classpath.shouldNotBeNull()
		classpath.shouldNotBeBlank()
	}

	test("resolveProbeCommand returns a non-null command in test environment") {
		val command = resolveProbeCommand("/test/lib.so")

		command.shouldNotBeNull()
		command.any { it.contains("java") || it.contains("probe") }.shouldBeTrue()
		command.last() shouldBe "/test/lib.so"
	}
})
