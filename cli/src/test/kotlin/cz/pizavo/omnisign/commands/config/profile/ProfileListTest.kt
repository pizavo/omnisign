package cz.pizavo.omnisign.commands.config.profile

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
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
 * Behavioral tests for the [ProfileList] command.
 *
 * Having no profiles is an ordinary answer, which points to `config profile create` and exits with
 * status 0. Failing to list them exits with status 1, so a script never takes a failure for an empty
 * list.
 */
class ProfileListTest : FunSpec({

	val manageProfile: ManageProfileUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageProfile } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageProfile) }

	test("lists every profile with its description") {
		coEvery { manageProfile.list() } returns mapOf(
			"qualified" to ProfileConfig(name = "qualified", description = "Qualified signatures"),
			"bare" to ProfileConfig(name = "bare"),
		).right()

		val result = Omnisign().test(listOf("config", "profile", "list"))

		result.statusCode shouldBe 0
		result.stdout shouldContain "● qualified — Qualified signatures"
		result.stdout shouldContain "● bare"
	}

	test("points to profile create when there are no profiles") {
		coEvery { manageProfile.list() } returns emptyMap<String, ProfileConfig>().right()

		val result = Omnisign().test(listOf("config", "profile", "list"))

		result.statusCode shouldBe 0
		result.stdout shouldContain "config profile create"
	}

	test("reports a failure on stderr and exits non-zero") {
		coEvery { manageProfile.list() } returns
			ConfigurationError.loadFailed(details = "config file is not valid JSON").left()

		val result = Omnisign().test(listOf("config", "profile", "list"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Details: config file is not valid JSON"
	}
})
