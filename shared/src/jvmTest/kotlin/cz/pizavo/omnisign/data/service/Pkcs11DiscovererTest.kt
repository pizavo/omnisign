package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Verifies the [Pkcs11Discoverer] orchestration pipeline: candidate enumeration →
 * process-isolated probing → serial-based deduplication, plus the discovery-running
 * signal and the read-only [Pkcs11Discoverer.getCachedTokens] path.
 *
 * Candidate-source heuristics live in [Pkcs11CandidateCollectorTest]; probe-cache
 * behaviour in [Pkcs11ProbeCacheTest].  These tests inject a real
 * [Pkcs11CandidateCollector] (default) and drive the probe seam through a fake
 * [Pkcs11Prober] wrapped in a [Pkcs11ProbeCache].
 */
class Pkcs11DiscovererTest : FunSpec({

	val noProbe: (String) -> List<Pkcs11TokenIdentity> = { emptyList() }

	/**
	 * Adapt a probe lambda into a [Pkcs11Prober] — only [Pkcs11Prober.probeIdentities] is
	 * exercised through the injected [Pkcs11ProbeCache]; the subprocess-level methods are
	 * unused here.
	 */
	fun proberOf(probe: (String) -> List<Pkcs11TokenIdentity>): Pkcs11Prober =
		object : Pkcs11Prober {
			override fun probeIdentities(libraryPath: String) = probe(libraryPath)
			override fun runProbe(libraryPath: String, timeoutSeconds: Long): Pkcs11SubprocessResult? = null
			override fun runCertProbe(libraryPath: String, timeoutSeconds: Long): Pkcs11SubprocessResult? = null
			override fun parseIdentities(stdout: String, libraryPath: String) = emptyList<Pkcs11TokenIdentity>()
		}

	/**
	 * Build a [Pkcs11ProbeCache] whose prober is driven by [probe], for injection into a
	 * [Pkcs11Discoverer] under test.
	 */
	fun probeCacheOf(
		crashBlacklist: Pkcs11CrashBlacklist = Pkcs11CrashBlacklist(),
		probe: (String) -> List<Pkcs11TokenIdentity> = noProbe,
	) = Pkcs11ProbeCache(crashBlacklist = crashBlacklist, prober = proberOf(probe))

	/**
	 * A [Pkcs11Discoverer] backed by a fresh [Pkcs11ProbeCache] whose prober runs [probe]
	 * and a real default [Pkcs11CandidateCollector].
	 */
	fun discoverer(probe: (String) -> List<Pkcs11TokenIdentity> = noProbe) =
		Pkcs11Discoverer(probeCache = probeCacheOf(probe = probe))

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
		val tokens = discoverer(identityProber).discoverTokens(appDataPkcs11Dir = dropDir)

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

	test("discoverTokens deduplicates same canonical path from multiple sources") {
		val tmpFile = File.createTempFile("acmevendor-pkcs11", ".so").also { it.deleteOnExit() }

		val identityProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
			if (path == tmpFile.absolutePath) {
				listOf(Pkcs11TokenIdentity(label = "Acme", serialNumber = "SN-DEDUP-PATH", libraryPath = path))
			} else {
				emptyList()
			}
		}
		discoverer(identityProber).discoverTokens(
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
		discoverer(identityProber).discoverTokens(
			userPkcs11Libraries = listOf("My Custom Token" to tmpFile.absolutePath)
		).any { it.path == tmpFile.absolutePath }.shouldBeTrue()
	}

	test("discoverTokens ignores user-supplied library when file does not exist") {
		discoverer().discoverTokens(
			userPkcs11Libraries = listOf("Ghost Token" to "/tmp/does-not-exist-pkcs11.so")
		).any { it.path == "/tmp/does-not-exist-pkcs11.so" }.shouldBeFalse()
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
		discoverer(identityProber).discoverTokens(appDataPkcs11Dir = dropDir)
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

		val tokens = discoverer(fakeProber).discoverTokens(
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

		val tokens = discoverer(fakeProber).discoverTokens(
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

		val tokens = discoverer(fakeProber).discoverTokens(
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

		val tokens = discoverer(fakeProber).discoverTokens(
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

		val tokens = discoverer(fakeProber).discoverTokens(
			userPkcs11Libraries = listOf("SafeNet eToken" to lib.absolutePath)
		)

		val hwToken = tokens.first { it.id == "pkcs11-0123456789ABCDEF" }
		hwToken.name shouldBe "John's eToken 5110"
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

		val discoverer = Pkcs11Discoverer(probeCache = probeCacheOf(probe = prober), probeParallelism = 2)

		discoverer.discoverTokens(
			userPkcs11Libraries = candidatePaths.map { "Lib" to it },
		)

		(peak.get() <= 2) shouldBe true
	}

	test("trimPkcs11Field strips null-byte padding used by SafeNet middleware") {
		val padded = "VP-SafeNet".toByteArray(Charsets.UTF_8) + ByteArray(22)
		padded.trimPkcs11Field() shouldBe "VP-SafeNet"
	}

	test("trimPkcs11Field strips space padding mandated by PKCS11 spec") {
		val padded = ("ABC123" + " ".repeat(10)).toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe "ABC123"
	}

	test("trimPkcs11Field handles mixed null-byte and space padding") {
		val padded = "SN-42".toByteArray(Charsets.UTF_8) + ByteArray(2) + " ".repeat(3).toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe "SN-42"
	}

	test("trimPkcs11Field returns empty string for all-null-byte field") {
		val padded = ByteArray(8)
		padded.trimPkcs11Field() shouldBe ""
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

		val tokens = discoverer(fakeProber).discoverTokens(
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

		val tokens = discoverer(fakeProber).discoverTokens(
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
		val d = discoverer { path ->
			listOf(Pkcs11TokenIdentity(label = "T", serialNumber = "SN", libraryPath = path))
		}

		d.discoverTokens(userPkcs11Libraries = listOf("Lib" to tmp.absolutePath))

		d.discoveryRunning.value.shouldBeFalse()
	}

	test("discoverTokens flips discoveryRunning false even when the cycle throws") {
		val tmp = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }
		val d = discoverer { error("boom") }

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
		val d = discoverer(fakeProber)

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
		val cache = probeCacheOf()
		val d = Pkcs11Discoverer(probeCache = cache)
		val identities = listOf(
			Pkcs11TokenIdentity(label = "Primed Token", serialNumber = "SN-PRIME", libraryPath = "/lib.so")
		)

		cache.primeCache("/lib.so", identities)

		val cached = d.getCachedTokens()
		cached.shouldHaveSize(1)
		cached.first().id shouldBe "pkcs11-SN-PRIME"
	}

	test("getCachedTokens returns empty after invalidateProbes clears the cache") {
		val cache = probeCacheOf { path ->
			listOf(Pkcs11TokenIdentity(label = "T", serialNumber = "SN", libraryPath = path))
		}
		val d = Pkcs11Discoverer(probeCache = cache)
		val tmp = File.createTempFile("eTPKCS11", ".dll").also { it.deleteOnExit() }

		d.discoverTokens(userPkcs11Libraries = listOf("Lib" to tmp.absolutePath))
		d.getCachedTokens().shouldHaveSize(1)

		cache.invalidateProbes()
		d.getCachedTokens().shouldBeEmpty()
	}
})
