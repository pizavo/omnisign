package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll

/**
 * Verifies [Pkcs11SubprocessResult] sealed variants, [runProbeSubprocess] subprocess
 * lifecycle, [probeTokenIdentitiesViaSubprocess] result interpretation, [signalName]
 * mapping, and the shared [MAX_STDERR_LOG_CHARS] constant.
 */
class Pkcs11SubprocessResultTest : FunSpec({

	afterEach { unmockkAll() }

	test("MAX_STDERR_LOG_CHARS is 2000") {
		MAX_STDERR_LOG_CHARS shouldBe 2000
	}

	test("Success holds pid and stdout") {
		val result = Pkcs11SubprocessResult.Success(pid = 42L, stdout = "label\tserial\n")

		result.pid shouldBe 42L
		result.stdout shouldBe "label\tserial\n"
	}

	test("Crashed holds pid, exitCode, and stderr") {
		val result = Pkcs11SubprocessResult.Crashed(pid = 43L, exitCode = 139, stderr = "segfault")

		result.pid shouldBe 43L
		result.exitCode shouldBe 139
		result.stderr shouldBe "segfault"
	}

	test("TimedOut holds pid") {
		val result = Pkcs11SubprocessResult.TimedOut(pid = 44L)

		result.pid shouldBe 44L
	}

	test("sealed interface allows exhaustive when matching") {
		val results: List<Pkcs11SubprocessResult> = listOf(
			Pkcs11SubprocessResult.Success(1L, "ok"),
			Pkcs11SubprocessResult.Crashed(2L, 1, "err"),
			Pkcs11SubprocessResult.TimedOut(3L),
		)

		val types = results.map { result ->
			when (result) {
				is Pkcs11SubprocessResult.Success -> "success"
				is Pkcs11SubprocessResult.Crashed -> "crashed"
				is Pkcs11SubprocessResult.TimedOut -> "timed-out"
			}
		}

		types shouldBe listOf("success", "crashed", "timed-out")
	}

	test("runProbeSubprocess returns Success with empty stdout for non-existent library") {
		val result = runProbeSubprocess("/tmp/omnisign-nonexistent-test-lib.so", 30L)

		result.shouldNotBeNull()
		val success = result.shouldBeInstanceOf<Pkcs11SubprocessResult.Success>()
		success.pid shouldBeGreaterThan 0L
		success.stdout.trim() shouldBe ""
	}

	test("probeTokenIdentitiesViaSubprocess parses identities from successful subprocess stdout") {
		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/lib.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 100L, stdout = "My Token\tSN-001\nAnother\tSN-002\n")

		val identities = probeTokenIdentitiesViaSubprocess("/test/lib.so")

		identities.shouldHaveSize(2)
		identities[0].label shouldBe "My Token"
		identities[0].serialNumber shouldBe "SN-001"
		identities[0].libraryPath shouldBe "/test/lib.so"
		identities[1].label shouldBe "Another"
		identities[1].serialNumber shouldBe "SN-002"
		identities[1].libraryPath shouldBe "/test/lib.so"
	}

	test("probeTokenIdentitiesViaSubprocess returns empty list for crashed subprocess") {
		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/crashed/lib.so", any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 101L, exitCode = 139, stderr = "segfault")

		probeTokenIdentitiesViaSubprocess("/crashed/lib.so").shouldBeEmpty()
	}

	test("probeTokenIdentitiesViaSubprocess returns empty list for timed-out subprocess") {
		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/hung/lib.so", any()) } returns
				Pkcs11SubprocessResult.TimedOut(pid = 102L)

		probeTokenIdentitiesViaSubprocess("/hung/lib.so").shouldBeEmpty()
	}

	test("probeTokenIdentitiesViaSubprocess returns empty list when command cannot be resolved") {
		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/no-cmd/lib.so", any()) } returns null

		probeTokenIdentitiesViaSubprocess("/no-cmd/lib.so").shouldBeEmpty()
	}

	test("probeTokenIdentitiesViaSubprocess skips lines without tab separator") {
		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/test/lib.so", any()) } returns
				Pkcs11SubprocessResult.Success(
					pid = 103L,
					stdout = "Valid Label\tSN-VALID\nno-tab-line\nAnother\tSN-TWO\n",
				)

		val identities = probeTokenIdentitiesViaSubprocess("/test/lib.so")

		identities.shouldHaveSize(2)
		identities[0].serialNumber shouldBe "SN-VALID"
		identities[1].serialNumber shouldBe "SN-TWO"
	}

	test("probeTokenIdentitiesViaSubprocess returns empty list when subprocess throws") {
		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/error/lib.so", any()) } throws RuntimeException("process start failed")

		probeTokenIdentitiesViaSubprocess("/error/lib.so").shouldBeEmpty()
	}

	test("probeTokenIdentitiesViaSubprocess returns empty list for Success with empty stdout") {
		mockkStatic(::runProbeSubprocess)
		every { runProbeSubprocess("/empty/lib.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 104L, stdout = "")

		probeTokenIdentitiesViaSubprocess("/empty/lib.so").shouldBeEmpty()
	}

	test("signalName maps common POSIX signals correctly") {
		signalName(1) shouldBe "SIGHUP"
		signalName(2) shouldBe "SIGINT"
		signalName(3) shouldBe "SIGQUIT"
		signalName(4) shouldBe "SIGILL"
		signalName(6) shouldBe "SIGABRT"
		signalName(7) shouldBe "SIGBUS"
		signalName(8) shouldBe "SIGFPE"
		signalName(9) shouldBe "SIGKILL"
		signalName(11) shouldBe "SIGSEGV"
		signalName(13) shouldBe "SIGPIPE"
		signalName(14) shouldBe "SIGALRM"
		signalName(15) shouldBe "SIGTERM"
	}

	test("signalName returns generic format for unmapped signal numbers") {
		signalName(5) shouldBe "signal 5"
		signalName(99) shouldBe "signal 99"
		signalName(0) shouldBe "signal 0"
	}
})

