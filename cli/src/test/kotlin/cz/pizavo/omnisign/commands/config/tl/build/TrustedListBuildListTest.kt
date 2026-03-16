package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module

/**
 * Behavioral tests for the [TrustedListBuildList] command verifying stdout output
 * and exit codes with mocked dependencies.
 */
class TrustedListBuildListTest : FunSpec({

	val configRepository: ConfigRepository = mockk()

	fun draft(name: String, territory: String = "CZ", operator: String = "Test Org", tsps: Int = 0) =
		CustomTrustedListDraft(
			name = name,
			territory = territory,
			schemeOperatorName = operator,
			trustServiceProviders = List(tsps) { TrustServiceProviderDraft(name = "TSP-$it") }
		)

	extension(
		KoinExtension(
			module {
				single { ManageTrustedListsUseCase(configRepository) }
				single { configRepository }
				single<PasswordCallback> { mockk() }
			},
			mode = KoinLifecycleMode.Test
		)
	)

	test("list command should be instantiable") {
		TrustedListBuildList().shouldNotBeNull()
	}

	test("list command name should be 'list'") {
		TrustedListBuildList().commandName shouldBe "list"
	}

	test("list prints empty-state hint when no drafts exist") {
		coEvery { configRepository.getCurrentConfig() } returns AppConfig()

		val result = Omnisign().test(listOf("config", "tl", "build", "list"))

		result.output shouldContain "No TL builder drafts found"
		result.output shouldContain "config tl build create"
		result.statusCode shouldBe 0
	}

	test("list prints each draft name, territory and scheme operator") {
		val config = AppConfig(
			tlDrafts = mapOf(
				"my-tl" to draft("my-tl", territory = "CZ", operator = "CTU Prague", tsps = 2),
				"empty-tl" to draft("empty-tl", territory = "SK", operator = "")
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns config

		val result = Omnisign().test(listOf("config", "tl", "build", "list"))

		result.output shouldContain "my-tl"
		result.output shouldContain "[CZ]"
		result.output shouldContain "CTU Prague"
		result.output shouldContain "TSPs           : 2"
		result.output shouldContain "empty-tl"
		result.output shouldContain "[SK]"
		result.output shouldContain "(not set)"
		result.statusCode shouldBe 0
	}

	test("list prints draft count in header") {
		val config = AppConfig(
			tlDrafts = mapOf(
				"a" to draft("a"),
				"b" to draft("b")
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns config

		val result = Omnisign().test(listOf("config", "tl", "build", "list"))

		result.output shouldContain "TL builder drafts (2)"
		result.statusCode shouldBe 0
	}
})


