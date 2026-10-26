package cz.pizavo.omnisign.commands.config.profile

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.koin.dsl.module

/**
 * Behavioral tests for the [ProfileRemove] command's outcome reporting.
 *
 * A profile that does not exist, or a configuration that cannot be saved without it, exits with
 * status 1, so a clean-up script never reports a profile as removed while it is still there.
 */
class ProfileRemoveTest : FunSpec({

	val manageProfile: ManageProfileUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageProfile } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageProfile) }

	test("confirms the removal on stdout") {
		coEvery { manageProfile.remove("qualified") } returns Unit.right()

		val result = Omnisign().test(listOf("config", "profile", "remove", "qualified"))

		result.statusCode shouldBe 0
		result.stdout shouldContain "Profile 'qualified' removed"
		coVerify { manageProfile.remove("qualified") }
	}

	test("names the missing profile on stderr and exits non-zero") {
		coEvery { manageProfile.remove("ghost") } returns ConfigurationError.profileNotFound("ghost").left()

		val result = Omnisign().test(listOf("config", "profile", "remove", "ghost"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Profile 'ghost' does not exist"
	}

	test("reports a save failure on stderr and exits non-zero") {
		coEvery { manageProfile.remove("qualified") } returns
			ConfigurationError.saveFailed(details = "disk full").left()

		val result = Omnisign().test(listOf("config", "profile", "remove", "qualified"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Details: disk full"
	}
})
