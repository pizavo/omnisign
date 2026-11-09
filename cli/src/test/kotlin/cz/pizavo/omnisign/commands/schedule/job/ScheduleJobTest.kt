package cz.pizavo.omnisign.commands.schedule.job

import arrow.core.left
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageRenewalJobsUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module

/**
 * Behavioral tests for how `schedule job list` and `schedule job remove` report a failure.
 *
 * Each says why on stderr and exits with status 1, so a script that sets up renewal never takes a
 * job it failed to remove for one that is gone.
 */
class ScheduleJobTest : FunSpec({

	val manageJobs: ManageRenewalJobsUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageJobs } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageJobs) }

	test("list reports a failure and exits non-zero") {
		coEvery { manageJobs.list() } returns
			ConfigurationError.loadFailed(details = "config file is not valid JSON").left()

		val result = Omnisign().test(listOf("schedule", "job", "list"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Failed to load jobs: Failed to load configuration"
		result.stderr shouldContain "Details: config file is not valid JSON"
	}

	test("remove names a job that does not exist and exits non-zero") {
		coEvery { manageJobs.remove("nightly") } returns ConfigurationError.renewalJobNotFound("nightly").left()

		val result = Omnisign().test(listOf("schedule", "job", "remove", "nightly"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Renewal job 'nightly' does not exist"
	}
})
