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
 */
class Pkcs11WarmupServiceTest : FunSpec({

	afterEach { unmockkAll() }

	test("warmup leaves a successful library off the blacklist") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Safe Lib" to "/test/safe.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/safe.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 200L, stdout = "")

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		blacklist.isCrashed("/test/safe.so").shouldBeFalse()
		signal.value.shouldBeTrue()
	}

	test("warmup blacklists a library whose subprocess crashes") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Crash Lib" to "/test/crash.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/crash.so", any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 201L, exitCode = 139, stderr = "SIGSEGV")

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		blacklist.isCrashed("/test/crash.so").shouldBeTrue()
		signal.value.shouldBeTrue()
	}

	test("warmup does not blacklist a timed-out library (retried via subprocess on demand)") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Hung Lib" to "/test/hung.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/hung.so", any()) } returns
				Pkcs11SubprocessResult.TimedOut(pid = 202L)

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		blacklist.isCrashed("/test/hung.so").shouldBeFalse()
		signal.value.shouldBeTrue()
	}

	test("warmup leaves library off blacklist when command cannot be resolved") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("No Cmd" to "/test/nocmd.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/nocmd.so", any()) } returns null

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		blacklist.isCrashed("/test/nocmd.so").shouldBeFalse()
		signal.value.shouldBeTrue()
	}

	test("warmup sets signal to true even when all candidates crash") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf(
			"Lib A" to "/test/a.so",
			"Lib B" to "/test/b.so",
		)
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess(any(), any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 300L, exitCode = 134, stderr = "")

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		blacklist.isCrashed("/test/a.so").shouldBeTrue()
		blacklist.isCrashed("/test/b.so").shouldBeTrue()
		signal.value.shouldBeTrue()
	}

	test("warmup honours maxParallelism — only that many probes run concurrently") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns
				(1..6).map { "Lib $it" to "/test/lib-$it.so" }
		val signal = MutableStateFlow(false)

		val concurrent = java.util.concurrent.atomic.AtomicInteger(0)
		val peak = java.util.concurrent.atomic.AtomicInteger(0)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess(any(), any()) } answers {
			val now = concurrent.incrementAndGet()
			peak.updateAndGet { kotlin.math.max(it, now) }
			Thread.sleep(50)
			concurrent.decrementAndGet()
			Pkcs11SubprocessResult.Success(pid = 1L, stdout = "")
		}

		Pkcs11WarmupService(discoverer, blacklist, signal, maxParallelism = 2).warmup()

		(peak.get() <= 2) shouldBe true
		signal.value.shouldBeTrue()
	}

	test("warmup skips when signal is already true") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		val signal = MutableStateFlow(true)

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		verify(exactly = 0) { discoverer.collectCandidates(any(), any()) }
	}

	test("warmup sets signal to true when no candidates found") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns emptyList()
		val signal = MutableStateFlow(false)

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		signal.value.shouldBeTrue()
	}

	test("warmup blacklists library when subprocess throws exception") {
		val blacklist = Pkcs11CrashBlacklist()
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Error Lib" to "/test/error.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/error.so", any()) } throws RuntimeException("process failed")

		Pkcs11WarmupService(discoverer, blacklist, signal).warmup()

		blacklist.isCrashed("/test/error.so").shouldBeTrue()
		signal.value.shouldBeTrue()
	}
})
