package cz.pizavo.omnisign.commands.config.tl

import arrow.core.left
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
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
 * Behavioral tests for how `config tl add`, `list` and `remove` report a failure.
 *
 * Each says why on stderr and exits with status 1, so a script never takes a trusted list it failed
 * to register for one that validation will use.
 */
class ConfigTrustedListTest : FunSpec({

	val manageTl: ManageTrustedListsUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageTl } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageTl) }

	test("add names a profile that does not exist and exits non-zero") {
		coEvery { manageTl.addTrustedList(any(), "ghost") } returns ConfigurationError.noProfileNamed("ghost").left()

		val result = Omnisign().test(
			listOf(
				"config", "tl", "add",
				"--name", "internal",
				"--source", "https://tl.omnisign.test/tl.xml",
				"--profile", "ghost",
			),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "No profile named 'ghost' found"
	}

	test("list names a profile that does not exist and exits non-zero") {
		coEvery { manageTl.listTrustedLists("ghost") } returns ConfigurationError.noProfileNamed("ghost").left()

		val result = Omnisign().test(listOf("config", "tl", "list", "--profile", "ghost"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No profile named 'ghost' found"
	}

	test("remove names a trusted list that is not registered and exits non-zero") {
		coEvery { manageTl.removeTrustedList("internal", null) } returns
			ConfigurationError.trustedListNotFound("internal").left()

		val result = Omnisign().test(listOf("config", "tl", "remove", "internal"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No trusted list named 'internal' found"
	}
})
