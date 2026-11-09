package cz.pizavo.omnisign.commands.schedule.job

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.data.util.isAbsoluteGlobRoot
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageRenewalJobsUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.koin.dsl.module

/**
 * Behavioral tests for the [ScheduleJobAdd] command: a relative glob is absolutized before the job
 * is persisted, and a glob whose root cannot be made absolute, or whose pattern cannot be parsed, is
 * rejected without saving anything. A rejected glob and a job that cannot be saved both end the
 * command with status 1, so a script never takes a job that was not stored for one that will run.
 */
class ScheduleJobAddTest : FunSpec({

	val manageJobs: ManageRenewalJobsUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageJobs } },
			mode = KoinLifecycleMode.Test,
		)
	)

	beforeTest { clearMocks(manageJobs) }

	test("absolutizes a relative glob and saves the job with an absolute root") {
		val saved = slot<RenewalJob>()
		coEvery { manageJobs.upsert(capture(saved)) } returns Unit.right()

		val result = Omnisign().test(listOf("schedule", "job", "add", "nightly", "-g", "*.pdf"))

		result.statusCode shouldBe 0
		saved.captured.globs.shouldNotBeEmpty()
		saved.captured.globs.forEach { isAbsoluteGlobRoot(it).shouldBeTrue() }
	}

	test("rejects a glob whose root cannot be made absolute, saves nothing and exits non-zero") {
		val unparseableGlob = Char(0) + "/docs/*.pdf"

		val result = Omnisign().test(listOf("schedule", "job", "add", "nightly", "-g", unparseableGlob))

		result.statusCode shouldBe 1
		result.stderr shouldContain "❌ Renewal globs must be absolute paths"
		coVerify(exactly = 0) { manageJobs.upsert(any()) }
	}

	test("rejects a glob whose pattern cannot be parsed, saves nothing and exits non-zero") {
		val result = Omnisign().test(listOf("schedule", "job", "add", "nightly", "-g", "invoices/[2026.pdf"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "❌ Renewal globs must be valid glob patterns"
		coVerify(exactly = 0) { manageJobs.upsert(any()) }
	}

	test("reports a job it could not save and exits non-zero") {
		coEvery { manageJobs.upsert(any()) } returns ConfigurationError.saveFailed(details = "disk full").left()

		val result = Omnisign().test(listOf("schedule", "job", "add", "nightly", "-g", "*.pdf"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "❌ Failed to save job: Failed to save configuration"
		result.stderr shouldContain "Details: disk full"
	}
})
