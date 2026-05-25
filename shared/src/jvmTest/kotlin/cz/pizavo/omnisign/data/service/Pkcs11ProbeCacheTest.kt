package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/**
 * Verifies [Pkcs11ProbeCache]'s probe delegation, crash-blacklist short-circuit, and the
 * caching invariants: successful non-empty probes are cached, empty/blacklisted ones are
 * not, [Pkcs11ProbeCache.primeCache] seeds the cache, and [Pkcs11ProbeCache.invalidateProbes]
 * forces a re-probe.
 *
 * The probe seam is the injected [Pkcs11Prober]; only [Pkcs11Prober.probeIdentities] is
 * exercised, so the subprocess-level methods return inert defaults.
 */
class Pkcs11ProbeCacheTest : FunSpec({

	/**
	 * Adapt a probe lambda into a [Pkcs11Prober] — only [Pkcs11Prober.probeIdentities] is
	 * exercised by [Pkcs11ProbeCache]; the subprocess-level methods are unused here.
	 */
	fun proberOf(probe: (String) -> List<Pkcs11TokenIdentity>): Pkcs11Prober =
		mockk<Pkcs11Prober>(relaxed = true) {
			every { probeIdentities(any()) } answers { probe(firstArg()) }
		}

	test("probeLibrary delegates to the configured prober") {
		val expected = listOf(
			Pkcs11TokenIdentity(label = "Test Token", serialNumber = "SN-999", libraryPath = "/test.so")
		)
		val cache = Pkcs11ProbeCache(prober = proberOf { expected })

		cache.probeLibrary("/test.so") shouldBe expected
	}

	test("probeLibrary returns empty list when the prober returns empty") {
		val cache = Pkcs11ProbeCache(prober = proberOf { emptyList() })

		cache.probeLibrary("/nonexistent.so").shouldBeEmpty()
	}

	test("probeLibrary skips blacklisted library without spawning a subprocess") {
		val blacklist = Pkcs11CrashBlacklist(crashThreshold = 1)
		blacklist.registerCrashed("/crashed/lib.so")

		val cache = Pkcs11ProbeCache(
			crashBlacklist = blacklist,
			prober = proberOf { error("should not be called") },
		)

		cache.probeLibrary("/crashed/lib.so").shouldBeEmpty()
	}

	test("probeLibrary delegates to the prober for non-blacklisted libraries") {
		val expected = listOf(
			Pkcs11TokenIdentity(label = "Sub Token", serialNumber = "SN-SUB", libraryPath = "/unknown/lib.so")
		)
		val cache = Pkcs11ProbeCache(
			crashBlacklist = Pkcs11CrashBlacklist(),
			prober = proberOf { expected },
		)

		cache.probeLibrary("/unknown/lib.so") shouldBe expected
	}

	test("probeLibrary caches a successful result and serves the next call from cache") {
		val calls = java.util.concurrent.atomic.AtomicInteger(0)
		val identities = listOf(
			Pkcs11TokenIdentity(label = "Cached", serialNumber = "SN-CACHE", libraryPath = "/lib.so")
		)
		val cache = Pkcs11ProbeCache(prober = proberOf {
			calls.incrementAndGet()
			identities
		})

		cache.probeLibrary("/lib.so") shouldBe identities
		cache.probeLibrary("/lib.so") shouldBe identities

		calls.get() shouldBe 1
	}

	test("probeLibrary does not cache empty results") {
		val calls = java.util.concurrent.atomic.AtomicInteger(0)
		val cache = Pkcs11ProbeCache(prober = proberOf {
			calls.incrementAndGet()
			emptyList()
		})

		cache.probeLibrary("/lib.so").shouldBeEmpty()
		cache.probeLibrary("/lib.so").shouldBeEmpty()

		calls.get() shouldBe 2
	}

	test("primeCache makes a subsequent probeLibrary skip the prober") {
		val identities = listOf(
			Pkcs11TokenIdentity(label = "Primed", serialNumber = "SN-PRIME", libraryPath = "/lib.so")
		)
		val cache = Pkcs11ProbeCache(prober = proberOf { error("should not be called") })

		cache.primeCache("/lib.so", identities)
		cache.probeLibrary("/lib.so") shouldBe identities
	}

	test("invalidateProbes forces the next probeLibrary to run the prober again") {
		val calls = java.util.concurrent.atomic.AtomicInteger(0)
		val identities = listOf(
			Pkcs11TokenIdentity(label = "X", serialNumber = "SN-X", libraryPath = "/lib.so")
		)
		val cache = Pkcs11ProbeCache(prober = proberOf {
			calls.incrementAndGet()
			identities
		})

		cache.probeLibrary("/lib.so")
		cache.invalidateProbes()
		cache.probeLibrary("/lib.so")

		calls.get() shouldBe 2
	}
})
