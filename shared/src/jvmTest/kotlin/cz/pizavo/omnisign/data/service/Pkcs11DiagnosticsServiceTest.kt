package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.TokenInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify

/**
 * Verifies [Pkcs11DiagnosticsService] correctly classifies subprocess outcomes, builds the
 * per-layer breakdown from the injected [Pkcs11CandidateCollector] helpers, and routes the
 * final token list through the shared dedup helper ([Pkcs11TokenInfoDeduplicator.buildTokenInfoList]).
 *
 * The probe seam is the injected [Pkcs11Prober] mock.
 */
class Pkcs11DiagnosticsServiceTest : FunSpec({

	afterEach { unmockkAll() }

	fun newDeduplicator(): Pkcs11TokenInfoDeduplicator = mockk<Pkcs11TokenInfoDeduplicator>().also { d ->
		every { d.buildTokenInfoList(any()) } returns emptyList()
	}

	fun newCandidateCollector(): Pkcs11CandidateCollector = mockk<Pkcs11CandidateCollector>().also { c ->
		every { c.discoverViaOs(any(), any()) } returns emptyList()
		every { c.collectCandidates(any(), any()) } returns emptyList()
		every { c.isPkcs11FileName(any()) } returns false
		every { c.deriveMiddlewareName(any()) } answers { firstArg() }
	}

	fun newProber(): Pkcs11Prober = mockk<Pkcs11Prober>().also { p ->
		every { p.runProbe(any(), any()) } returns null
		every { p.runCertProbe(any(), any()) } returns null
		every { p.parseIdentities(any(), any()) } returns emptyList()
	}

	fun newConfigRepo(): ConfigRepository = mockk<ConfigRepository>().also { repo ->
		coEvery { repo.getCurrentConfig() } returns AppConfig(global = GlobalConfig())
	}

	fun newPcscMonitor(): PcscMonitorService = mockk<PcscMonitorService>().also { monitor ->
		every { monitor.currentReaders() } returns emptyList()
	}

	test("runDiagnostics returns a populated environment block") {
		val service = Pkcs11DiagnosticsService(
			newDeduplicator(), newCandidateCollector(), newProber(), newConfigRepo(), newPcscMonitor(),
		)

		val report = service.runDiagnostics()

		report.environment.osName shouldNotBe ""
		report.environment.javaHome shouldNotBe ""
		report.environment.classpathChars shouldNotBe -1
	}

	test("runDiagnostics with no candidates produces empty probes and tokens") {
		val service = Pkcs11DiagnosticsService(
			newDeduplicator(), newCandidateCollector(), newProber(), newConfigRepo(), newPcscMonitor(),
		)

		val report = service.runDiagnostics()

		report.mergedCandidates.shouldBeEmpty()
		report.probes.shouldBeEmpty()
		report.tokens.shouldBeEmpty()
	}

	test("runDiagnostics records SUCCESS probe with parsed identities") {
		val collector = newCandidateCollector()
		every { collector.collectCandidates(any(), any()) } returns
				listOf("Safe Lib" to "/test/safe.so")

		val prober = newProber()
		every { prober.runProbe("/test/safe.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 100L, stdout = "MyToken\tABC123\n")
		every { prober.parseIdentities(any(), any()) } returns
				listOf(Pkcs11TokenIdentity(label = "MyToken", serialNumber = "ABC123", libraryPath = "/test/safe.so"))

		val report = Pkcs11DiagnosticsService(
			newDeduplicator(), collector, prober, newConfigRepo(), newPcscMonitor(),
		).runDiagnostics()

		report.probes shouldHaveSize 1
		val probe = report.probes.single()
		probe.outcome shouldBe Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.SUCCESS
		probe.exitCode shouldBe 0
		probe.pid shouldBe 100L
		probe.identities shouldContain Pkcs11DiagnosticsReport.Identity("MyToken", "ABC123")
	}

	test("runDiagnostics records CRASHED probe with stderr snippet") {
		val collector = newCandidateCollector()
		every { collector.collectCandidates(any(), any()) } returns
				listOf("Crash Lib" to "/test/crash.so")

		val prober = newProber()
		every { prober.runProbe("/test/crash.so", any()) } returns
				Pkcs11SubprocessResult.Crashed(pid = 200L, exitCode = 139, stderr = "SIGSEGV at 0x0")

		val report = Pkcs11DiagnosticsService(
			newDeduplicator(), collector, prober, newConfigRepo(), newPcscMonitor(),
		).runDiagnostics()

		val probe = report.probes.single()
		probe.outcome shouldBe Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.CRASHED
		probe.exitCode shouldBe 139
		probe.stderrSnippet!! shouldContain "SIGSEGV"
		probe.identities.shouldBeEmpty()
	}

	test("runDiagnostics records TIMED_OUT probe") {
		val collector = newCandidateCollector()
		every { collector.collectCandidates(any(), any()) } returns
				listOf("Hung Lib" to "/test/hung.so")

		val prober = newProber()
		every { prober.runProbe("/test/hung.so", any()) } returns
				Pkcs11SubprocessResult.TimedOut(pid = 300L)

		val report = Pkcs11DiagnosticsService(
			newDeduplicator(), collector, prober, newConfigRepo(), newPcscMonitor(),
		).runDiagnostics()

		val probe = report.probes.single()
		probe.outcome shouldBe Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.TIMED_OUT
		probe.pid shouldBe 300L
		probe.exitCode shouldBe null
	}

	test("runDiagnostics records NO_COMMAND when subprocess command cannot be resolved") {
		val collector = newCandidateCollector()
		every { collector.collectCandidates(any(), any()) } returns
				listOf("No Cmd" to "/test/nocmd.so")

		val prober = newProber()
		every { prober.runProbe("/test/nocmd.so", any()) } returns null

		val report = Pkcs11DiagnosticsService(
			newDeduplicator(), collector, prober, newConfigRepo(), newPcscMonitor(),
		).runDiagnostics()

		val probe = report.probes.single()
		probe.outcome shouldBe Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.NO_COMMAND
		probe.pid shouldBe null
		probe.exitCode shouldBe null
	}

	test("runDiagnostics routes final tokens through buildTokenInfoList") {
		val collector = newCandidateCollector()
		every { collector.collectCandidates(any(), any()) } returns
				listOf("Lib" to "/test/lib.so")

		val deduplicator = newDeduplicator()
		every { deduplicator.buildTokenInfoList(any()) } returns listOf(
			TokenInfo(
				id = "pkcs11-XYZ",
				name = "Final Token",
				type = TokenType.PKCS11,
				path = "/test/lib.so",
				requiresPin = true,
			)
		)

		val prober = newProber()
		every { prober.runProbe("/test/lib.so", any()) } returns
				Pkcs11SubprocessResult.Success(pid = 1L, stdout = "Token\tXYZ\n")

		val report = Pkcs11DiagnosticsService(
			deduplicator, collector, prober, newConfigRepo(), newPcscMonitor(),
		).runDiagnostics()

		report.tokens shouldHaveSize 1
		report.tokens.single().id shouldBe "pkcs11-XYZ"
		report.tokens.single().name shouldBe "Final Token"
		verify { deduplicator.buildTokenInfoList(any()) }
	}

	test("runDiagnostics breaks down candidates by source layer") {
		val collector = newCandidateCollector()
		every { collector.discoverViaOs(any(), any()) } returns listOf("OS Lib" to "/os/lib.so")

		val report = Pkcs11DiagnosticsService(
			newDeduplicator(), collector, newProber(), newConfigRepo(), newPcscMonitor(),
		).runDiagnostics()

		report.candidatesByLayer.osNative shouldHaveSize 1
		report.candidatesByLayer.osNative.single().name shouldBe "OS Lib"
		report.candidatesByLayer.dropDir.shouldBeEmpty()
		report.candidatesByLayer.userSupplied.shouldBeEmpty()
	}

	test("runDiagnostics measures total elapsed time") {
		val service = Pkcs11DiagnosticsService(
			newDeduplicator(), newCandidateCollector(), newProber(), newConfigRepo(), newPcscMonitor(),
		)

		val report = service.runDiagnostics()

		(report.totalElapsedMillis >= 0L) shouldBe true
	}
})
