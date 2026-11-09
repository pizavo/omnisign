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
 * Behavioral tests for the [ProfileUse] command, which picks the profile every later command signs,
 * timestamps and validates with.
 *
 * The literal `none`, in any case, clears the active profile instead of naming one. A profile that
 * cannot be activated exits with status 1, since a script that runs `config profile use` before
 * signing would otherwise go on to sign under whichever profile was active before.
 */
class ProfileUseTest : FunSpec({

	val manageProfile: ManageProfileUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageProfile } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageProfile) }

	test("activates the named profile") {
		coEvery { manageProfile.setActive("qualified") } returns Unit.right()

		val result = Omnisign().test(listOf("config", "profile", "use", "qualified"))

		result.statusCode shouldBe 0
		result.stdout shouldContain "Active profile set to 'qualified'"
		coVerify { manageProfile.setActive("qualified") }
	}

	test("clears the active profile on none, whatever its case") {
		coEvery { manageProfile.setActive(null) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "profile", "use", "None"))

		result.statusCode shouldBe 0
		result.stdout shouldContain "Active profile cleared"
		coVerify { manageProfile.setActive(null) }
	}

	test("names the missing profile on stderr and exits non-zero") {
		coEvery { manageProfile.setActive("ghost") } returns ConfigurationError.profileNotFound("ghost").left()

		val result = Omnisign().test(listOf("config", "profile", "use", "ghost"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Profile 'ghost' does not exist"
	}
})
