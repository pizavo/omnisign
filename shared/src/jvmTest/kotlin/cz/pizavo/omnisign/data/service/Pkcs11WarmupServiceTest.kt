package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Verifies [Pkcs11WarmupService] warmup orchestration: subprocess result handling,
 * [Pkcs11SessionManager] registration, and warmup signal lifecycle.
 */
class Pkcs11WarmupServiceTest : FunSpec({

	afterEach { unmockkAll() }

	test("warmup registers safe for library whose subprocess succeeds") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		every { sessionManager.hasSession("/test/safe.so") } returns true
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Safe Lib" to "/test/safe.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/safe.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 200L, stdout = "")

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify { sessionManager.registerSafe("/test/safe.so") }
		verify(exactly = 0) { sessionManager.registerCrashed(any()) }
		signal.value.shouldBeTrue()
	}

	test("warmup registers crashed for library whose subprocess crashes") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Crash Lib" to "/test/crash.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/crash.so", any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 201L, exitCode = 139, stderr = "SIGSEGV")

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify { sessionManager.registerCrashed("/test/crash.so") }
		verify(exactly = 0) { sessionManager.registerSafe(any()) }
		signal.value.shouldBeTrue()
	}

	test("warmup registers crashed for library whose subprocess times out") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Hung Lib" to "/test/hung.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/hung.so", any()) } returns
				Pkcs11SubprocessResult.TimedOut(pid = 202L)

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify { sessionManager.registerCrashed("/test/hung.so") }
		verify(exactly = 0) { sessionManager.registerSafe(any()) }
		signal.value.shouldBeTrue()
	}

	test("warmup skips library when command cannot be resolved") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("No Cmd" to "/test/nocmd.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/nocmd.so", any()) } returns null

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify(exactly = 0) { sessionManager.registerSafe(any()) }
		verify(exactly = 0) { sessionManager.registerCrashed(any()) }
		signal.value.shouldBeTrue()
	}

	test("warmup sets signal to true even when all candidates crash") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf(
			"Lib A" to "/test/a.so",
			"Lib B" to "/test/b.so",
		)
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess(any(), any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 300L, exitCode = 134, stderr = "")

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify(exactly = 2) { sessionManager.registerCrashed(any()) }
		signal.value.shouldBeTrue()
	}

	test("warmup skips when signal is already true") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		val discoverer = mockk<Pkcs11Discoverer>()
		val signal = MutableStateFlow(true)

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify(exactly = 0) { discoverer.collectCandidates(any(), any()) }
	}

	test("warmup sets signal to true when no candidates found") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns emptyList()
		val signal = MutableStateFlow(false)

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify(exactly = 0) { sessionManager.registerSafe(any()) }
		verify(exactly = 0) { sessionManager.registerCrashed(any()) }
		signal.value.shouldBeTrue()
	}

	test("warmup registers crashed when subprocess throws exception") {
		val sessionManager = mockk<Pkcs11SessionManager>(relaxed = true)
		val discoverer = mockk<Pkcs11Discoverer>()
		every { discoverer.collectCandidates(any(), any()) } returns listOf("Error Lib" to "/test/error.so")
		val signal = MutableStateFlow(false)

		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/error.so", any()) } throws RuntimeException("process failed")

		Pkcs11WarmupService(discoverer, sessionManager, signal).warmup()

		verify { sessionManager.registerCrashed("/test/error.so") }
		signal.value.shouldBeTrue()
	}
})

