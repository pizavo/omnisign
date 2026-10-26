package cz.pizavo.omnisign.commands.config

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
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
 * tests pin that it goes to the keyring, that it is refused rather than saved where no keyring
 * could keep it, and that nothing is saved when the keyring does not keep it.
 */
class ConfigSetTest : FunSpec({

	val configRepository: ConfigRepository = mockk()
	val setGlobalConfig: SetGlobalConfigUseCase = mockk()
	val credentialStore: CredentialStore = mockk(relaxed = true)
	val passwordCallback: PasswordCallback = mockk(relaxed = true)

	extension(
		KoinExtension(
			module {
				single { configRepository }
				single { setGlobalConfig }
				single { credentialStore }
				single { passwordCallback }
			},
			mode = KoinLifecycleMode.Test,
		)
	)

	beforeTest {
		clearMocks(configRepository, setGlobalConfig, credentialStore, passwordCallback)
		every { credentialStore.isPersistent } returns true
		every { credentialStore.setPassword(any(), any(), any()) } returns Unit.right()
		coEvery { configRepository.getCurrentConfig() } returns AppConfig()
		coEvery { setGlobalConfig(any()) } returns Unit.right()
	}

	/**
	 * Run `config set` with [args] against a current configuration of [base], and return the
	 * [GlobalConfig] the command would persist, by applying the captured update lambda to [base].
	 */
	suspend fun applyConfigSet(vararg args: String, base: GlobalConfig = GlobalConfig()): GlobalConfig {
		val update = slot<GlobalConfig.() -> GlobalConfig>()
		coEvery { configRepository.getCurrentConfig() } returns AppConfig(global = base)
		coEvery { setGlobalConfig(capture(update)) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "set", *args))

		result.statusCode shouldBe 0
		return update.captured(base)
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

	test("keys the TSA password by the username already saved when none is given") {
		val base = GlobalConfig(
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "tsa-user")
		)

		val updated = applyConfigSet("--timestamp-password", "s3cret", base = base)

		verify { credentialStore.setPassword(ConfigSet.TSA_CREDENTIAL_SERVICE, "tsa-user", "s3cret") }
		updated.timestampServer?.credentialKey shouldBe "tsa-user"
		updated.timestampServer?.url shouldBe "https://tsa.example.com"
	}

	test("saves nothing, not even the other settings, when the keyring does not keep the TSA password") {
		every { credentialStore.setPassword(any(), any(), any()) } returns
			ConfigurationError.passwordStoreFailed("Error code 1312").left()

		val result = Omnisign().test(
			listOf(
				"config", "set", "--hash-algorithm", "SHA512",
				"--timestamp-username", "tsa-user", "--timestamp-password", "s3cret",
			)
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Failed to store the password in the system credential store"
		result.stderr shouldContain "nothing was saved"
		result.stderr shouldContain "Error code 1312"
		result.stderr shouldContain "OMNISIGN_TIMESTAMP_PASSWORD"
		coVerify(exactly = 0) { setGlobalConfig(any()) }
	}

	test("refuses to store the TSA password when no keyring can keep it") {
		every { credentialStore.isPersistent } returns false

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
