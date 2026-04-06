package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
		val tmpFile = File.createTempFile("opensc-pkcs11", ".so").also { it.deleteOnExit() }
		
		discoverer().discoverTokens(
			userPkcs11Libraries = listOf(
				"OpenSC (source 1)" to tmpFile.absolutePath,
				"OpenSC (source 2)" to tmpFile.absolutePath,
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
})
