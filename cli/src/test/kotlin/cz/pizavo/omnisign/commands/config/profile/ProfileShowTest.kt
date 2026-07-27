package cz.pizavo.omnisign.commands.config.profile

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.koin.dsl.module

/**
 * Behavioral tests for the [ProfileShow] command's rendering, which is the only place a user can
 * check what a profile actually holds before signing with it.
 *
 * Two properties matter beyond "it prints something". An unset field must read as inheriting from
 * the global configuration rather than as an absent or empty value, because the two mean very
 * different things at signing time. And a stored TSA password must be reported as living in the
 * keychain without the command ever reaching for the secret itself.
 */
class ProfileShowTest : FunSpec({

	val manageProfile: ManageProfileUseCase = mockk()
	val getConfig: GetConfigUseCase = mockk()

	extension(
		KoinExtension(
			module {
				single { manageProfile }
				single { getConfig }
			},
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageProfile, getConfig) }

	/** Run `config profile show` for [profile], with [active] as the configured active profile. */
	suspend fun show(profile: ProfileConfig, active: String? = null, vararg args: String): String {
		coEvery { getConfig() } returns AppConfig(global = GlobalConfig(), activeProfile = active).right()
		coEvery { manageProfile.get(profile.name) } returns profile.right()

		return Omnisign().test(listOf("config", "profile", "show", *args, profile.name)).stdout
	}

	test("reports every unset override as inherited from the global configuration") {
		val output = show(ProfileConfig(name = "bare"))

		output shouldContain "PROFILE: bare"
		output shouldContain "Hash algorithm   : inherit from global"
		output shouldContain "Encryption alg.  : inherit from global"
		output shouldContain "Signature level  : inherit from global"
		output shouldContain "Description      : (none)"
	}

	test("prints the overrides a profile does carry") {
		val output = show(
			ProfileConfig(
				name = "qualified",
				description = "Qualified signatures",
				hashAlgorithm = HashAlgorithm.SHA512,
			),
		)

		output shouldContain "Description      : Qualified signatures"
		output shouldContain "Hash algorithm   : SHA512"
	}

	test("marks the profile when it is the active one") {
		val output = show(ProfileConfig(name = "qualified"), active = "qualified")

		output shouldContain "◀ active"
	}

	test("leaves the marker off a profile that is not active") {
		val output = show(ProfileConfig(name = "qualified"), active = "other")

		output shouldNotContain "◀ active"
	}

	test("reports an absent timestamp block as inherited rather than missing") {
		val output = show(ProfileConfig(name = "bare"))

		output shouldContain "[Timestamp Server]"
		output shouldContain "(inherit from global)"
	}

	test("reports a stored TSA password as living in the keychain, never printing it") {
		val output = show(
			ProfileConfig(
				name = "qualified",
				timestampServer = TimestampServerConfig(
					url = "https://tsa.example.com",
					username = "tsa-user",
					credentialKey = "tsa-user",
					timeout = 5000,
				),
			),
		)

		output shouldContain "URL              : https://tsa.example.com"
		output shouldContain "Auth             : password stored in keychain"
		output shouldContain "Timeout          : 5000 ms"
	}

	test("reports no auth when the TSA block carries no stored credential") {
		val output = show(
			ProfileConfig(
				name = "open",
				timestampServer = TimestampServerConfig(url = "https://tsa.example.com", timeout = 30000),
			),
		)

		output shouldContain "Auth             : none"
		output shouldContain "Username         : (none)"
	}

	test("falls back to the active profile when no name is given") {
		coEvery { getConfig() } returns
			AppConfig(global = GlobalConfig(), activeProfile = "qualified").right()
		coEvery { manageProfile.get("qualified") } returns ProfileConfig(name = "qualified").right()

		val output = Omnisign().test(listOf("config", "profile", "show")).stdout

		output shouldContain "PROFILE: qualified"
	}

	test("explains itself when there is neither a name nor an active profile") {
		coEvery { getConfig() } returns AppConfig(global = GlobalConfig()).right()

		val result = Omnisign().test(listOf("config", "profile", "show"))

		result.stderr shouldContain "no active profile is set"
		coVerify(exactly = 0) { manageProfile.get(any()) }
	}

	test("names the missing profile on stderr") {
		coEvery { getConfig() } returns AppConfig(global = GlobalConfig()).right()
		coEvery { manageProfile.get("ghost") } returns ConfigurationError.profileNotFound("ghost").left()

		val result = Omnisign().test(listOf("config", "profile", "show", "ghost"))

		result.stderr shouldContain "Profile 'ghost' does not exist"
	}
})
