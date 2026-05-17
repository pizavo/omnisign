package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Verifies [Pkcs11WarmupService] orchestration: subprocess result handling,
 * [Pkcs11CrashBlacklist] updates, parallelism cap, and signal lifecycle.
 *
 * The probe seam is the injected [Pkcs11Prober] mock — warmup branches on the
 * [Pkcs11SubprocessResult] it returns and parses successes via [Pkcs11Prober.parseIdentities].
 */
class Pkcs11WarmupServiceTest : FunSpec({

	afterEach { unmockkAll() }

	/**
	 * Build a [Pkcs11CrashBlacklist] whose threshold is `1` so a single recorded crash
	 * already counts as blacklisted.  Lets the warmup tests focus on the warmup → blacklist
	 * plumbing without entangling them with the threshold/decay semantics, which are tested
	 * directly in [Pkcs11CrashBlacklistTest].
	 */
	fun newImmediateBlacklist() = Pkcs11CrashBlacklist(crashThreshold = 1)

	/**
	 * A relaxed [Pkcs11ProbeCache] mock.  Warmup only touches it via
	 * [Pkcs11ProbeCache.primeCache] on a successful probe, which these tests don't assert,
	 * so a no-op stand-in keeps them focused on the warmup → blacklist plumbing.
	 */
	fun newProbeCache() = mockk<Pkcs11ProbeCache>(relaxUnitFun = true)

	test("warmup leaves a successful library off the blacklist") {
		val blacklist = newImmediateBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Safe Lib" to "/test/safe.so")
		val signal = MutableStateFlow(false)

		val prober = mockk<Pkcs11Prober>()
		every { prober.runProbe("/test/safe.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 200L, stdout = "")
		every { prober.parseIdentities(any(), any()) } returns emptyList()

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		blacklist.isCrashed("/test/safe.so").shouldBeFalse()
		signal.value.shouldBeTrue()
	}

	test("warmup blacklists a library whose subprocess crashes") {
		val blacklist = newImmediateBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Crash Lib" to "/test/crash.so")
		val signal = MutableStateFlow(false)

		val prober = mockk<Pkcs11Prober>()
		every { prober.runProbe("/test/crash.so", any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 201L, exitCode = 139, stderr = "SIGSEGV")

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		blacklist.isCrashed("/test/crash.so").shouldBeTrue()
		signal.value.shouldBeTrue()
	}

	test("warmup does not blacklist a timed-out library (retried via subprocess on demand)") {
		val blacklist = newImmediateBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Hung Lib" to "/test/hung.so")
		val signal = MutableStateFlow(false)

		val prober = mockk<Pkcs11Prober>()
		every { prober.runProbe("/test/hung.so", any()) } returns
				Pkcs11SubprocessResult.TimedOut(pid = 202L)

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		blacklist.isCrashed("/test/hung.so").shouldBeFalse()
		signal.value.shouldBeTrue()
	}

	test("warmup leaves library off blacklist when command cannot be resolved") {
		val blacklist = newImmediateBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns listOf("No Cmd" to "/test/nocmd.so")
		val signal = MutableStateFlow(false)

		val prober = mockk<Pkcs11Prober>()
		every { prober.runProbe("/test/nocmd.so", any()) } returns null

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		blacklist.isCrashed("/test/nocmd.so").shouldBeFalse()
		signal.value.shouldBeTrue()
	}

	test("warmup sets signal to true even when all candidates crash") {
		val blacklist = newImmediateBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns listOf(
			"Lib A" to "/test/a.so",
			"Lib B" to "/test/b.so",
		)
		val signal = MutableStateFlow(false)

		val prober = mockk<Pkcs11Prober>()
		every { prober.runProbe(any(), any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 300L, exitCode = 134, stderr = "")

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		blacklist.isCrashed("/test/a.so").shouldBeTrue()
		blacklist.isCrashed("/test/b.so").shouldBeTrue()
		signal.value.shouldBeTrue()
	}

	test("warmup honours maxParallelism — only that many probes run concurrently") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns
				(1..6).map { "Lib $it" to "/test/lib-$it.so" }
		val signal = MutableStateFlow(false)

		val concurrent = java.util.concurrent.atomic.AtomicInteger(0)
		val peak = java.util.concurrent.atomic.AtomicInteger(0)

		val prober = mockk<Pkcs11Prober>()
		every { prober.runProbe(any(), any()) } answers {
			val now = concurrent.incrementAndGet()
			peak.updateAndGet { kotlin.math.max(it, now) }
			Thread.sleep(50)
			concurrent.decrementAndGet()
			Pkcs11SubprocessResult.Success(pid = 1L, stdout = "")
		}
		every { prober.parseIdentities(any(), any()) } returns emptyList()

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal, maxParallelism = 2).warmup()

		(peak.get() <= 2) shouldBe true
		signal.value.shouldBeTrue()
	}

	test("warmup skips when signal is already true") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		val prober = mockk<Pkcs11Prober>()
		val signal = MutableStateFlow(true)

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		verify(exactly = 0) { discoverer.collectCandidates(any(), any()) }
	}

	test("warmup sets signal to true when no candidates found") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns emptyList()
		val prober = mockk<Pkcs11Prober>()
		val signal = MutableStateFlow(false)

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		signal.value.shouldBeTrue()
	}

	test("warmup blacklists library when subprocess throws exception") {
		val blacklist = newImmediateBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Error Lib" to "/test/error.so")
		val signal = MutableStateFlow(false)

		val prober = mockk<Pkcs11Prober>()
		every { prober.runProbe("/test/error.so", any()) } throws RuntimeException("process failed")

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		blacklist.isCrashed("/test/error.so").shouldBeTrue()
		signal.value.shouldBeTrue()
	}

	test("warmup wraps the validation pass in begin/endDiscovery so the unified flag is published") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } returns emptyList()
		val prober = mockk<Pkcs11Prober>()
		val signal = MutableStateFlow(false)

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		verifyOrder {
			discoverer.beginDiscovery()
			discoverer.collectCandidates(any(), any())
			discoverer.endDiscovery()
		}
	}

	test("warmup calls endDiscovery even when the pass throws so the flag does not get stuck") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		every { discoverer.collectCandidates(any(), any()) } throws RuntimeException("enumeration failed")
		val prober = mockk<Pkcs11Prober>()
		val signal = MutableStateFlow(false)

		runCatching { Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup() }

		verify(exactly = 1) { discoverer.beginDiscovery() }
		verify(exactly = 1) { discoverer.endDiscovery() }
	}

	test("warmup skips begin/endDiscovery when the signal already says warmup is done") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		val prober = mockk<Pkcs11Prober>()
		val signal = MutableStateFlow(true)

		Pkcs11WarmupService(discoverer, newProbeCache(), prober, blacklist, signal).warmup()

		verify(exactly = 0) { discoverer.beginDiscovery() }
		verify(exactly = 0) { discoverer.endDiscovery() }
	}
})
