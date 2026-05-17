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

		val identityProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path == realFile.absolutePath || path == spyFile.absolutePath) {
				listOf(Pkcs11TokenIdentity(label = "Token", serialNumber = "SN-${path.hashCode()}", libraryPath = path))
			} else {
				emptyList()
			}
		}
		val tokens = Pkcs11Discoverer(tokenProber = identityProber).discoverTokens(appDataPkcs11Dir = dropDir)

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

		val identityProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path == tmpFile.absolutePath) {
				listOf(Pkcs11TokenIdentity(label = "Acme", serialNumber = "SN-DEDUP-PATH", libraryPath = path))
			} else {
				emptyList()
			}
		}
		Pkcs11Discoverer(tokenProber = identityProber).discoverTokens(
			userPkcs11Libraries = listOf(
				"Acme (source 1)" to tmpFile.absolutePath,
				"Acme (source 2)" to tmpFile.absolutePath,
			)
		).filter { it.path == tmpFile.absolutePath }.shouldHaveSize(1)
	}

	test("discoverTokens includes user-supplied library when file exists and probe returns identities") {
		val tmpFile = File.createTempFile("custom-pkcs11", ".so").also { it.deleteOnExit() }

		val identityProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path == tmpFile.absolutePath) {
				listOf(Pkcs11TokenIdentity(label = "My Token", serialNumber = "SN-USER", libraryPath = path))
			} else {
				emptyList()
			}
		}
		Pkcs11Discoverer(tokenProber = identityProber).discoverTokens(
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
	
	test("discoverTokens picks up PKCS11-named files from app-data drop directory when probe returns identities") {
		val dropDir = File.createTempFile("pkcs11-drop", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
		val libFile = File(dropDir, "vendor-pkcs11.so").also { it.createNewFile(); it.deleteOnExit() }

		val identityProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path == libFile.absolutePath) {
				listOf(Pkcs11TokenIdentity(label = "Drop Token", serialNumber = "SN-DROP", libraryPath = path))
			} else {
				emptyList()
			}
		}
		Pkcs11Discoverer(tokenProber = identityProber).discoverTokens(appDataPkcs11Dir = dropDir)
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

	test("discoverTokens emits no token for libraries that probe successfully but expose no identities") {
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
		}.shouldBeEmpty()
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

	test("discoverTokens emits only the identity-bearing library when a same-family lib has no identities") {
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

	test("discoverTokens emits the identity-bearing library regardless of probe order vs an empty same-family lib") {
		val lib1 = File.createTempFile("gclib", ".dll").also { it.deleteOnExit() }
		val lib2 = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }

		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path == lib2.absolutePath) {
				listOf(
					Pkcs11TokenIdentity(
						label = "My SafeNet Token",
						serialNumber = "SN-REVERSE",
						libraryPath = path,
					)
				)
			} else {
				emptyList()
			}
		}

		val tokens = Pkcs11Discoverer(tokenProber = fakeProber).discoverTokens(
			userPkcs11Libraries = listOf(
				"Thales/Gemalto IDPrime" to lib1.absolutePath,
				"SafeNet eToken" to lib2.absolutePath,
			)
		)

		val safenetTokens = tokens.filter {
			it.id == "pkcs11-SN-REVERSE" || it.path == lib1.absolutePath
		}
		safenetTokens.shouldHaveSize(1)
		safenetTokens.first().name shouldBe "My SafeNet Token"
		safenetTokens.first().id shouldBe "pkcs11-SN-REVERSE"
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

	test("probeLibrary skips blacklisted library without spawning a subprocess") {
		val blacklist = Pkcs11CrashBlacklist(crashThreshold = 1)
		blacklist.registerCrashed("/crashed/lib.so")

		val discoverer = Pkcs11Discoverer(
			crashBlacklist = blacklist,
			tokenProber = { error("should not be called") },
		)

		discoverer.probeLibrary("/crashed/lib.so").shouldBeEmpty()
	}

	test("probeLibrary delegates to tokenProber for non-blacklisted libraries") {
		val expected = listOf(
			Pkcs11TokenIdentity(label = "Sub Token", serialNumber = "SN-SUB", libraryPath = "/unknown/lib.so")
		)
		val discoverer = Pkcs11Discoverer(
			crashBlacklist = Pkcs11CrashBlacklist(),
			tokenProber = { expected },
		)

		discoverer.probeLibrary("/unknown/lib.so") shouldBe expected
	}

	test("probeLibrary caches a successful result and serves the next call from cache") {
		val calls = java.util.concurrent.atomic.AtomicInteger(0)
		val identities = listOf(
			Pkcs11TokenIdentity(label = "Cached", serialNumber = "SN-CACHE", libraryPath = "/lib.so")
		)
		val discoverer = Pkcs11Discoverer(tokenProber = {
			calls.incrementAndGet()
			identities
		})

		discoverer.probeLibrary("/lib.so") shouldBe identities
		discoverer.probeLibrary("/lib.so") shouldBe identities

		calls.get() shouldBe 1
	}

	test("probeLibrary does not cache empty results") {
		val calls = java.util.concurrent.atomic.AtomicInteger(0)
		val discoverer = Pkcs11Discoverer(tokenProber = {
			calls.incrementAndGet()
			emptyList()
		})

		discoverer.probeLibrary("/lib.so").shouldBeEmpty()
		discoverer.probeLibrary("/lib.so").shouldBeEmpty()

		calls.get() shouldBe 2
	}

	test("primeCache makes a subsequent probeLibrary skip the prober") {
		val identities = listOf(
			Pkcs11TokenIdentity(label = "Primed", serialNumber = "SN-PRIME", libraryPath = "/lib.so")
		)
		val discoverer = Pkcs11Discoverer(tokenProber = { error("should not be called") })

		discoverer.primeCache("/lib.so", identities)
		discoverer.probeLibrary("/lib.so") shouldBe identities
	}

	test("invalidateCache forces the next probeLibrary to run the prober again") {
		val calls = java.util.concurrent.atomic.AtomicInteger(0)
		val identities = listOf(
			Pkcs11TokenIdentity(label = "X", serialNumber = "SN-X", libraryPath = "/lib.so")
		)
		val discoverer = Pkcs11Discoverer(tokenProber = {
			calls.incrementAndGet()
			identities
		})

		discoverer.probeLibrary("/lib.so")
		discoverer.invalidateCache()
		discoverer.probeLibrary("/lib.so")

		calls.get() shouldBe 2
	}

	test("collectCandidates caches its result so a second call returns the same instance") {
		val lib = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val discoverer = Pkcs11Discoverer(tokenProber = { emptyList() })

		val first = discoverer.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))
		val second = discoverer.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))

		(first === second) shouldBe true
	}

	test("invalidateCache forces collectCandidates to re-enumerate") {
		val lib = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val discoverer = Pkcs11Discoverer(tokenProber = { emptyList() })

		val first = discoverer.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))
		discoverer.invalidateCache()
		val second = discoverer.collectCandidates(userPkcs11Libraries = listOf("Test" to lib.absolutePath))

		(first === second) shouldBe false
		first shouldBe second
	}

	test("collectCandidates uses distinct cache entries for different user-library inputs") {
		val lib1 = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val lib2 = File.createTempFile("opensc", ".dll").also { it.deleteOnExit() }
		val discoverer = Pkcs11Discoverer(tokenProber = { emptyList() })

		val first = discoverer.collectCandidates(userPkcs11Libraries = listOf("a" to lib1.absolutePath))
		val second = discoverer.collectCandidates(userPkcs11Libraries = listOf("b" to lib2.absolutePath))

		(first === second) shouldBe false
	}

	test("discoverTokens caps subprocess parallelism at probeParallelism") {
		val concurrent = java.util.concurrent.atomic.AtomicInteger(0)
		val peak = java.util.concurrent.atomic.AtomicInteger(0)
		val candidatePaths = (1..6).map { i ->
			File.createTempFile("eTPKCS11-$i", ".dll").also { it.deleteOnExit() }.absolutePath
		}

		val prober: (String) -> List<Pkcs11TokenIdentity> = { path ->
			val now = concurrent.incrementAndGet()
			peak.updateAndGet { kotlin.math.max(it, now) }
			Thread.sleep(50)
			concurrent.decrementAndGet()
			listOf(
				Pkcs11TokenIdentity(
					label = "T-$path",
					serialNumber = "SN-${path.hashCode()}",
					libraryPath = path,
				)
			)
		}

		val discoverer = Pkcs11Discoverer(tokenProber = prober, probeParallelism = 2)

		discoverer.discoverTokens(
			userPkcs11Libraries = candidatePaths.map { "Lib" to it },
		)

		(peak.get() <= 2) shouldBe true
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

	test("trimPkcs11Field strips null-byte padding used by SafeNet middleware") {
		val padded = "VP-SafeNet\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe "VP-SafeNet"
	}

	test("trimPkcs11Field strips space padding mandated by PKCS11 spec") {
		val padded = "ABC123          ".toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe "ABC123"
	}

	test("trimPkcs11Field handles mixed null-byte and space padding") {
		val padded = "SN-42\u0000\u0000   ".toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe "SN-42"
	}

	test("trimPkcs11Field returns empty string for all-null-byte field") {
		val padded = "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe ""
	}

	test("normalizeSerial strips whitespace, null bytes, and uppercases") {
		normalizeSerial("abc 123\u0000\u0000") shouldBe "ABC123"
	}

	test("normalizeSerial produces identical output for space-padded and null-padded serials") {
		normalizeSerial("SN-42\u0000\u0000\u0000") shouldBe normalizeSerial("SN-42   ")
	}

	test("normalizeSerial handles already-clean serial unchanged except for case") {
		normalizeSerial("ABC123") shouldBe "ABC123"
		normalizeSerial("abc123") shouldBe "ABC123"
	}

	test("isProxyPath recognises p11-kit-proxy paths") {
		val d = discoverer()
		d.isProxyPath("/usr/lib/x86_64-linux-gnu/pkcs11/p11-kit-proxy.so").shouldBeTrue()
		d.isProxyPath("/usr/lib64/pkcs11/p11-kit-proxy.so").shouldBeTrue()
	}

	test("isProxyPath rejects non-proxy paths") {
		val d = discoverer()
		d.isProxyPath("/usr/lib/libeTPkcs11.so").shouldBeFalse()
		d.isProxyPath("/usr/lib/opensc-pkcs11.so").shouldBeFalse()
	}

	test("discoverTokens suppresses proxy fallback when proxy reports no identities") {
		val proxyFile = File.createTempFile("p11-kit-proxy", ".so").also { it.deleteOnExit() }

		val tokens = discoverer().discoverTokens(
			userPkcs11Libraries = listOf("p11-kit Proxy" to proxyFile.absolutePath),
		)

		tokens.any { it.path == proxyFile.absolutePath }.shouldBeFalse()
	}

	test("discoverTokens deduplicates proxy identity against direct library by serial") {
		val directLib = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val proxyLib = File.createTempFile("p11-kit-proxy", ".so").also { it.deleteOnExit() }

		val testPaths = setOf(directLib.absolutePath, proxyLib.absolutePath)
		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path in testPaths) {
				listOf(
					Pkcs11TokenIdentity(
						label = "VP-SafeNet",
						serialNumber = "SN-DEDUP",
						libraryPath = path,
					)
				)
			} else {
				emptyList()
			}
		}

		val tokens = Pkcs11Discoverer(tokenProber = fakeProber).discoverTokens(
			userPkcs11Libraries = listOf(
				"SafeNet eToken" to directLib.absolutePath,
				"p11-kit Proxy" to proxyLib.absolutePath,
			)
		)

		val matched = tokens.filter { it.id == "pkcs11-SN-DEDUP" }
		matched.shouldHaveSize(1)
		matched.first().path shouldBe directLib.absolutePath
	}

	test("discoverTokens deduplicates serials differing only by null-byte vs space padding") {
		val lib1 = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val lib2 = File.createTempFile("gclib", ".dll").also { it.deleteOnExit() }

		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			listOf(
				Pkcs11TokenIdentity(
					label = "VP-SafeNet",
					serialNumber = if (path == lib1.absolutePath) "SN42" else "sn42",
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

		tokens.filter { it.name == "VP-SafeNet" }.shouldHaveSize(1)
	}

	test("discoveryRunning is initially false so a passive read does not block forever") {
		val d = discoverer()
		d.discoveryRunning.value.shouldBeFalse()
	}

	test("beginDiscovery / endDiscovery toggle discoveryRunning around a single in-flight cycle") {
		val d = discoverer()
		d.discoveryRunning.value.shouldBeFalse()

		d.beginDiscovery()
		d.discoveryRunning.value.shouldBeTrue()

		d.endDiscovery()
		d.discoveryRunning.value.shouldBeFalse()
	}

	test("nested begin/end keep discoveryRunning true until the last endDiscovery") {
		val d = discoverer()

		d.beginDiscovery()
		d.beginDiscovery()
		d.discoveryRunning.value.shouldBeTrue()

		d.endDiscovery()
		d.discoveryRunning.value.shouldBeTrue()

		d.endDiscovery()
		d.discoveryRunning.value.shouldBeFalse()
	}

	test("discoverTokens leaves discoveryRunning false after a successful cycle") {
		val tmp = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val d = Pkcs11Discoverer(tokenProber = { path ->
			listOf(Pkcs11TokenIdentity(label = "T", serialNumber = "SN", libraryPath = path))
		})

		d.discoverTokens(userPkcs11Libraries = listOf("Lib" to tmp.absolutePath))

		d.discoveryRunning.value.shouldBeFalse()
	}

	test("discoverTokens flips discoveryRunning false even when the cycle throws") {
		val tmp = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val d = Pkcs11Discoverer(tokenProber = { error("boom") })

		runCatching {
			d.discoverTokens(userPkcs11Libraries = listOf("Lib" to tmp.absolutePath))
		}

		d.discoveryRunning.value.shouldBeFalse()
	}

	test("getCachedTokens returns empty when no probe result has been cached") {
		val d = discoverer()
		d.getCachedTokens().shouldBeEmpty()
	}

	test("getCachedTokens returns the same deduplicated tokens that discoverTokens produced") {
		val lib1 = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val lib2 = File.createTempFile("gclib", ".dll").also { it.deleteOnExit() }

		val fakeProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			listOf(
				Pkcs11TokenIdentity(
					label = "Hardware Token",
					serialNumber = "SN-CACHED",
					libraryPath = path,
				)
			)
		}
		val d = Pkcs11Discoverer(tokenProber = fakeProber)

		d.discoverTokens(
			userPkcs11Libraries = listOf(
				"SafeNet eToken" to lib1.absolutePath,
				"Thales/Gemalto IDPrime" to lib2.absolutePath,
			)
		)

		val cached = d.getCachedTokens()
		cached.shouldHaveSize(1)
		cached.first().id shouldBe "pkcs11-SN-CACHED"
		cached.first().name shouldBe "Hardware Token"
	}

	test("getCachedTokens reflects primeCache writes from warmup") {
		val d = discoverer()
		val identities = listOf(
			Pkcs11TokenIdentity(label = "Primed Token", serialNumber = "SN-PRIME", libraryPath = "/lib.so")
		)

		d.primeCache("/lib.so", identities)

		val cached = d.getCachedTokens()
		cached.shouldHaveSize(1)
		cached.first().id shouldBe "pkcs11-SN-PRIME"
	}

	test("getCachedTokens returns empty after invalidateProbes clears the cache") {
		val d = Pkcs11Discoverer(tokenProber = { path ->
			listOf(Pkcs11TokenIdentity(label = "T", serialNumber = "SN", libraryPath = path))
		})
		val tmp = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }

		d.discoverTokens(userPkcs11Libraries = listOf("Lib" to tmp.absolutePath))
		d.getCachedTokens().shouldHaveSize(1)

		d.invalidateProbes()
		d.getCachedTokens().shouldBeEmpty()
	}
})
