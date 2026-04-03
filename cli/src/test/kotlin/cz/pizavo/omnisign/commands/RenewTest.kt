package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.data.service.OsNotificationService
import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.model.result.RenewJobResult
import cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import org.koin.dsl.module

/**
 * Behavioral tests for the [Renew] command verifying stdout/stderr output,
 * exit codes, JSON mode, and notification dispatch.
 */
class RenewTest : FunSpec({

	val renewBatchUseCase: RenewBatchUseCase = mockk()
	val notificationService: OsNotificationService = mockk(relaxed = true)

	extension(
		KoinExtension(
			module {
				single { renewBatchUseCase }
				single { notificationService }
				single<PasswordCallback> { mockk() }
			},
			mode = KoinLifecycleMode.Test
		)
	)

	beforeTest {
		clearMocks(renewBatchUseCase, notificationService)
	}

	test("renew command should be instantiable") {
		Renew().shouldNotBeNull()
	}

	test("renew command name should be 'renew'") {
		Renew().commandName shouldBe "renew"
	}

	test("no jobs configured prints informational message") {
		coEvery { renewBatchUseCase(jobName = null, dryRun = false) } returns
			RenewBatchResult(jobs = emptyList())

		val result = Omnisign().test(listOf("renew"))

		result.output shouldContain "No renewal jobs configured"
		result.statusCode shouldBe 0
	}

	test("successful renewal prints summary") {
		coEvery { renewBatchUseCase(jobName = null, dryRun = false) } returns RenewBatchResult(
			checked = 2, renewed = 1, skipped = 1, errors = 0,
			jobs = listOf(
				RenewJobResult(
					name = "archive",
					renewed = 1,
					files = listOf(
						RenewFileStatus("/tmp/a.pdf", RenewFileStatus.Status.RENEWED),
						RenewFileStatus("/tmp/b.pdf", RenewFileStatus.Status.SKIPPED),
					),
				)
			),
		)

		val result = Omnisign().test(listOf("renew"))

		result.output shouldContain "RENEWAL SUMMARY"
		result.output shouldContain "Checked : 2"
		result.output shouldContain "Renewed : 1"
		result.statusCode shouldBe 0
	}

	test("errors in renewal result in exit code 1") {
		coEvery { renewBatchUseCase(jobName = null, dryRun = false) } returns RenewBatchResult(
			checked = 1, renewed = 0, skipped = 0, errors = 1,
			jobs = listOf(
				RenewJobResult(
					name = "job1",
					errors = 1,
					files = listOf(
						RenewFileStatus("/tmp/fail.pdf", RenewFileStatus.Status.ERROR, message = "TSA down"),
					),
				)
			),
		)

		val result = Omnisign().test(listOf("renew"))

		result.statusCode shouldBe 1
		result.output shouldContain "Errors  : 1"
	}

	test("unknown job name prints error") {
		coEvery { renewBatchUseCase(jobName = "ghost", dryRun = false) } returns null

		val result = Omnisign().test(listOf("renew", "-j", "ghost"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "not found"
	}

	test("--json outputs structured JSON on success") {
		coEvery { renewBatchUseCase(jobName = null, dryRun = false) } returns RenewBatchResult(
			checked = 1, renewed = 1, skipped = 0, errors = 0,
			jobs = listOf(
				RenewJobResult(
					name = "daily",
					renewed = 1,
					files = listOf(
						RenewFileStatus("/tmp/ok.pdf", RenewFileStatus.Status.RENEWED),
					),
				)
			),
		)

		val result = Omnisign().test(listOf("--json", "renew"))

		result.output shouldContain "\"success\""
		result.output shouldContain "\"renewed\":1"
		result.statusCode shouldBe 0
	}

	test("notification is sent for job with notify=true") {
		coEvery { renewBatchUseCase(jobName = null, dryRun = false) } returns RenewBatchResult(
			checked = 1, renewed = 1, skipped = 0, errors = 0,
			jobs = listOf(
				RenewJobResult(name = "notified", renewed = 1, errors = 0, notify = true),
			),
		)

		Omnisign().test(listOf("renew"))

		verify(atLeast = 1) { notificationService.notify(any(), any(), any()) }
	}

	test("no notification sent during dry-run") {
		coEvery { renewBatchUseCase(jobName = null, dryRun = true) } returns RenewBatchResult(
			checked = 1, renewed = 1, skipped = 0, errors = 0, dryRun = true,
			jobs = listOf(
				RenewJobResult(name = "dry", renewed = 1, errors = 0, notify = true),
			),
		)

		Omnisign().test(listOf("renew", "--dry-run"))

		verify(exactly = 0) { notificationService.notify(any(), any(), any()) }
	}
})
