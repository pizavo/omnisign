package cz.pizavo.omnisign.commands.config

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
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
 * Behavioral tests for the [ConfigShow] command's rendering of the global configuration.
 *
 * The distinction under test is between a value that is genuinely set and one that is merely
 * defaulted: an unset encryption algorithm reads as inferred from the key, an unset constraint level
 * names the DSS default it will fall back to. Collapsing the two would let a user believe they had
 * configured something they had not — and this command is where they would go to check.
 */
class ConfigShowTest : FunSpec({

	val getConfig: GetConfigUseCase = mockk()
	val trustStore: TrustStore = mockk()

	extension(
		KoinExtension(
			module {
				single { getConfig }
				single { trustStore }
			},
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest {
		clearMocks(getConfig, trustStore)
		coEvery { trustStore.list(any()) } returns emptyList<TrustedCertificate>().right()
	}

	/** Run `config show` against [global] and return stdout. */
	suspend fun show(global: GlobalConfig = GlobalConfig()): String {
		coEvery { getConfig() } returns AppConfig(global = global).right()
		return Omnisign().test(listOf("config", "show")).stdout
	}

	test("prints the global defaults") {
		val output = show(
			GlobalConfig(
				defaultHashAlgorithm = HashAlgorithm.SHA512,
				defaultEncryptionAlgorithm = EncryptionAlgorithm.ECDSA,
			),
		)

		output shouldContain "APPLICATION CONFIGURATION"
		output shouldContain "Default hash algorithm      : SHA512"
		output shouldContain "Default encryption algorithm: ECDSA"
	}

	test("reports an unset encryption algorithm as inferred from the key") {
		val output = show(GlobalConfig(defaultEncryptionAlgorithm = null))

		output shouldContain "Default encryption algorithm: infer from key"
	}

	test("reports an unconfigured timestamp server as not set") {
		val output = show(GlobalConfig(timestampServer = null))

		output shouldContain "Timestamp server       : not set"
	}

	test("prints the configured timestamp server url") {
		val output = show(
			GlobalConfig(
				timestampServer = TimestampServerConfig(url = "https://tsa.example.com", timeout = 30000),
			),
		)

		output shouldContain "Timestamp server       : https://tsa.example.com"
	}

	test("names the fallback for an unset algorithm constraint level") {
		val output = show(
			GlobalConfig(
				validation = ValidationConfig(
					algorithmConstraints = AlgorithmConstraintsConfig(expirationLevel = null),
				),
			),
		)

		output shouldContain
			"Algo expiry level      : default (${AlgorithmConstraintsConfig.DEFAULT.expirationLevel})"
	}

	test("prints a configured algorithm constraint level without the default marker") {
		val output = show(
			GlobalConfig(
				validation = ValidationConfig(
					algorithmConstraints = AlgorithmConstraintsConfig(
						expirationLevel = AlgorithmConstraintLevel.FAIL,
					),
				),
			),
		)

		output shouldContain "Algo expiry level      : FAIL"
	}

	test("lists the per-algorithm expiry overrides when any are set") {
		val output = show(
			GlobalConfig(
				validation = ValidationConfig(
					algorithmConstraints = AlgorithmConstraintsConfig(
						expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01"),
					),
				),
			),
		)

		output shouldContain "RIPEMD160"
		output shouldContain "2030-01-01"
	}

	test("reports a load failure and exits non-zero") {
		coEvery { getConfig() } returns
			ConfigurationError.loadFailed(details = "config file is not valid JSON").left()

		val result = Omnisign().test(listOf("config", "show"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Failed to load configuration"
		result.stderr shouldContain "config file is not valid JSON"
	}
})
