package cz.pizavo.omnisign.commands.config

import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.koin.dsl.module

/**
 * Behavioral tests for the [ConfigSet] command, guarding the validation-related options against
 * silently becoming no-ops: both `--validation-policy` and `--check-revocation` were once declared
 * and reported as saved while never reaching the persisted [ValidationConfig]. The TSA password
 * tests pin that it goes to the keyring, and that it is refused rather than saved where no keyring
 * could keep it.
 */
class ConfigSetTest : FunSpec({

	val setGlobalConfig: SetGlobalConfigUseCase = mockk()
	val credentialStore: CredentialStore = mockk(relaxed = true)
	val passwordCallback: PasswordCallback = mockk(relaxed = true)

	extension(
		KoinExtension(
			module {
				single { setGlobalConfig }
				single { credentialStore }
				single { passwordCallback }
			},
			mode = KoinLifecycleMode.Test,
		)
	)

	beforeTest {
		clearMocks(setGlobalConfig, credentialStore, passwordCallback)
		every { credentialStore.isPersistent } returns true
	}

	/**
	 * Run `config set` with [args] and return the [GlobalConfig] the command would persist,
	 * by applying the captured update lambda to [base].
	 */
	suspend fun applyConfigSet(vararg args: String, base: GlobalConfig = GlobalConfig()): GlobalConfig {
		val update = slot<GlobalConfig.() -> GlobalConfig>()
		coEvery { setGlobalConfig(capture(update)) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "set", *args))

		result.statusCode shouldBe 0
		return update.captured(base)
	}

	/**
	 * Make the mocked use case apply each update to [base] as it is called, as the real one does,
	 * so that work the command does inside the update — prompting, storing a password — happens.
	 */
	fun applyUpdatesTo(base: GlobalConfig) {
		coEvery { setGlobalConfig(any()) } coAnswers {
			firstArg<GlobalConfig.() -> GlobalConfig>().invoke(base)
			Unit.right()
		}
	}

	test("--check-revocation false is applied to the persisted validation config") {
		val updated = applyConfigSet("--check-revocation", "false")

		updated.validation.checkRevocation shouldBe false
	}

	test("--validation-policy is applied to the persisted validation config") {
		val updated = applyConfigSet("--validation-policy", "CUSTOM_FILE")

		updated.validation.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
	}

	test("omitting the validation options leaves the existing values untouched") {
		val base = GlobalConfig(
			validation = ValidationConfig(
				policyType = ValidationPolicyType.CUSTOM_FILE,
				checkRevocation = false,
			)
		)

		val updated = applyConfigSet("--use-eu-lotl", "false", base = base)

		updated.validation.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
		updated.validation.checkRevocation shouldBe false
		updated.validation.useEuLotl shouldBe false
	}

	test("stores the TSA password in the keyring, keyed by the username") {
		val updated = applyConfigSet("--timestamp-username", "tsa-user", "--timestamp-password", "s3cret")

		verify { credentialStore.setPassword(ConfigSet.TSA_CREDENTIAL_SERVICE, "tsa-user", "s3cret") }
		updated.timestampServer?.credentialKey shouldBe "tsa-user"
	}

	test("refuses to store the TSA password when no keyring can keep it") {
		every { credentialStore.isPersistent } returns false
		applyUpdatesTo(GlobalConfig())

		val result = Omnisign().test(
			listOf("config", "set", "--timestamp-username", "tsa-user", "--timestamp-password", "s3cret")
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "no keyring is available"
		result.stderr shouldContain "OMNISIGN_TIMESTAMP_PASSWORD"
		coVerify(exactly = 0) { setGlobalConfig(any()) }
		verify(exactly = 0) { credentialStore.setPassword(any(), any(), any()) }
	}

	test("does not prompt for a TSA password that no keyring could keep") {
		every { credentialStore.isPersistent } returns false
		applyUpdatesTo(GlobalConfig())

		val result = Omnisign().test(listOf("config", "set", "--timestamp-password", "-"))

		result.statusCode shouldBe 1
		verify(exactly = 0) { passwordCallback.requestPassword(any(), any()) }
	}

	test("saves the other TSA settings without a keyring") {
		every { credentialStore.isPersistent } returns false

		val updated = applyConfigSet(
			"--timestamp-url", "https://tsa.example.com",
			"--timestamp-username", "tsa-user",
		)

		updated.timestampServer?.url shouldBe "https://tsa.example.com"
		updated.timestampServer?.username shouldBe "tsa-user"
	}
})
