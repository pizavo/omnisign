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
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.koin.dsl.module

/**
 * Behavioral tests for the [ConfigSet] command, guarding the validation-related options against
 * silently becoming no-ops: both `--validation-policy` and `--check-revocation` were once declared
 * and reported as saved while never reaching the persisted [ValidationConfig].
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

	beforeTest { clearMocks(setGlobalConfig, credentialStore, passwordCallback) }

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
})
