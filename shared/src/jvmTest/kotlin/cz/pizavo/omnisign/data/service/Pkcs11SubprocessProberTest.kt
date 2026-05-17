package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll

/**
 * Verifies [Pkcs11SubprocessResult] sealed variants, [Pkcs11SubprocessProber] subprocess
 * lifecycle, [Pkcs11Prober.probeIdentities] result interpretation, [Pkcs11Prober.parseIdentities]
 * parsing, and [signalName] mapping.
 *
 * Result interpretation is driven by `spyk`-stubbing the prober's own [Pkcs11SubprocessProber.runProbe]
 * so the spawn is isolated while the real classify/parse path is exercised.
 */
class Pkcs11SubprocessProberTest : FunSpec({

	afterEach { unmockkAll() }

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

	test("runProbe returns Success with empty stdout for non-existent library") {
		val result = Pkcs11SubprocessProber().runProbe("/tmp/omnisign-nonexistent-test-lib.so", 30L)

		result.shouldNotBeNull()
		val success = result.shouldBeInstanceOf<Pkcs11SubprocessResult.Success>()
		success.pid shouldBeGreaterThan 0L
		success.stdout.trim() shouldBe ""
	}

	test("parseIdentities parses tab-separated rows and records the library path") {
		val prober = Pkcs11SubprocessProber()

		val identities = prober.parseIdentities("My Token\tSN-001\nAnother\tSN-002\n", "/test/lib.so")

		identities.shouldHaveSize(2)
		identities[0].label shouldBe "My Token"
		identities[0].serialNumber shouldBe "SN-001"
		identities[0].libraryPath shouldBe "/test/lib.so"
		identities[1].label shouldBe "Another"
		identities[1].serialNumber shouldBe "SN-002"
		identities[1].libraryPath shouldBe "/test/lib.so"
	}

	test("parseIdentities skips lines without a tab separator") {
		val prober = Pkcs11SubprocessProber()

		val identities = prober.parseIdentities(
			"Valid Label\tSN-VALID\nno-tab-line\nAnother\tSN-TWO\n",
			"/test/lib.so",
		)

		identities.shouldHaveSize(2)
		identities[0].serialNumber shouldBe "SN-VALID"
		identities[1].serialNumber shouldBe "SN-TWO"
	}

	test("probeIdentities parses identities from a successful subprocess") {
		val prober = spyk(Pkcs11SubprocessProber())
		every { prober.runProbe("/test/lib.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 100L, stdout = "My Token\tSN-001\nAnother\tSN-002\n")

		val identities = prober.probeIdentities("/test/lib.so")

		identities.shouldHaveSize(2)
		identities[0].serialNumber shouldBe "SN-001"
		identities[1].serialNumber shouldBe "SN-002"
	}

	test("probeIdentities returns empty list for crashed subprocess") {
		val prober = spyk(Pkcs11SubprocessProber())
		every { prober.runProbe("/crashed/lib.so", any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 101L, exitCode = 139, stderr = "segfault")

		prober.probeIdentities("/crashed/lib.so").shouldBeEmpty()
	}

	test("probeIdentities returns empty list for timed-out subprocess") {
		val prober = spyk(Pkcs11SubprocessProber())
		every { prober.runProbe("/hung/lib.so", any()) } returns
				Pkcs11SubprocessResult.TimedOut(pid = 102L)

		prober.probeIdentities("/hung/lib.so").shouldBeEmpty()
	}

	test("probeIdentities returns empty list when command cannot be resolved") {
		val prober = spyk(Pkcs11SubprocessProber())
		every { prober.runProbe("/no-cmd/lib.so", any()) } returns null

		prober.probeIdentities("/no-cmd/lib.so").shouldBeEmpty()
	}

	test("probeIdentities returns empty list when the subprocess throws") {
		val prober = spyk(Pkcs11SubprocessProber())
		every { prober.runProbe("/error/lib.so", any()) } throws RuntimeException("process start failed")

		prober.probeIdentities("/error/lib.so").shouldBeEmpty()
	}

	test("probeIdentities returns empty list for Success with empty stdout") {
		val prober = spyk(Pkcs11SubprocessProber())
		every { prober.runProbe("/empty/lib.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 104L, stdout = "")

		prober.probeIdentities("/empty/lib.so").shouldBeEmpty()
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
