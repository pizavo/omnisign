package cz.pizavo.omnisign.commands.config.tl.build

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.data.service.TrustedListCompiler
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.engine.spec.tempfile
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.koin.dsl.module
import java.io.File

/**
 * Behavioral tests for how the `config tl build` subcommands that change a draft, or turn it into a
 * trusted list, report a failure: `compile`, the `create` wizard, `delete`, `tsp remove` and the
 * `service` subcommands.
 *
 * Each says why on stderr and exits with status 1, so a script that builds a list step by step
 * stops at the first step that did not take. The `create` wizard saves the draft after the scheme
 * step and after every provider; a save that fails stops it there, instead of asking for more input
 * that would not be saved either.
 */
class TrustedListBuildTest : FunSpec({

	val manageTl: ManageTrustedListsUseCase = mockk()
	val compiler: TrustedListCompiler = mockk()

	extension(
		KoinExtension(
			module {
				single { manageTl }
				single { compiler }
			},
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageTl, compiler) }

	val tmpDir = tempdir()
	val certificate = tempfile(suffix = ".pem")
	val draft = CustomTrustedListDraft(name = "internal", territory = "CZ", schemeOperatorName = "OmniSign Test Operator")

	/** The answers the `create` wizard asks for before it first saves the draft. */
	val schemeAnswers = listOf(
		"CZ",
		"OmniSign Test Operator",
		"Internal trust anchors",
		"https://tl.omnisign.test",
		"Technicka 2",
		"Praha",
		"CZ",
		"",
		"",
		"mailto:tl@omnisign.test",
	)

	/** The answers that add one provider, with no services, once the draft is saved. */
	val providerAnswers = listOf(
		"y",
		"OmniSign Test TSP",
		"",
		"https://tsp.omnisign.test",
		"Technicka 2",
		"Praha",
		"CZ",
		"",
		"",
		"mailto:tsp@omnisign.test",
		"n",
	)

	test("compile names a draft that does not exist and exits non-zero") {
		coEvery { manageTl.getDraft("internal") } returns ConfigurationError.draftNotFound("internal").left()

		val result = Omnisign().test(listOf("config", "tl", "build", "compile", "internal"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No TL draft named 'internal' found"
		verify(exactly = 0) { compiler.compileTo(any(), any()) }
	}

	test("compile reports a draft that does not compile, and registers nothing") {
		coEvery { manageTl.getDraft("internal") } returns draft.right()
		every { compiler.compileTo(any(), any()) } throws IllegalStateException("the operator address is missing")

		val result = Omnisign().test(
			listOf(
				"config", "tl", "build", "compile", "internal",
				"--out", File(tmpDir, "internal.xml").absolutePath,
				"--register",
			),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Compilation failed: the operator address is missing"
		coVerify(exactly = 0) { manageTl.addTrustedList(any(), any()) }
	}

	test("compile reports a list it compiled but could not register, and exits non-zero") {
		coEvery { manageTl.getDraft("internal") } returns draft.right()
		every { compiler.compileTo(any(), any()) } just Runs
		coEvery { manageTl.addTrustedList(any(), "ghost") } returns ConfigurationError.noProfileNamed("ghost").left()

		val result = Omnisign().test(
			listOf(
				"config", "tl", "build", "compile", "internal",
				"--out", File(tmpDir, "registered.xml").absolutePath,
				"--register", "--profile", "ghost",
			),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Failed to register: No profile named 'ghost' found"
	}

	test("create stops when the draft cannot be saved, and exits non-zero") {
		coEvery { manageTl.upsertDraft(any()) } returns ConfigurationError.saveFailed(details = "disk full").left()

		val result = Omnisign().test(
			listOf("config", "tl", "build", "create", "internal"),
			stdin = schemeAnswers.joinToString("\n", postfix = "\n"),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Failed to save draft: Failed to save configuration"
		result.stdout shouldNotContain "Trust Service Providers"
		coVerify(exactly = 0) { manageTl.upsertTsp(any(), any()) }
	}

	test("create stops at a provider it cannot save, and exits non-zero") {
		coEvery { manageTl.upsertDraft(any()) } returns Unit.right()
		coEvery { manageTl.upsertTsp("internal", any()) } returns
			ConfigurationError.saveFailed(details = "disk full").left()

		val result = Omnisign().test(
			listOf("config", "tl", "build", "create", "internal"),
			stdin = (schemeAnswers + providerAnswers).joinToString("\n", postfix = "\n"),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Failed to save TSP 'OmniSign Test TSP': Failed to save configuration"
		result.stdout shouldNotContain "Add another Trust Service Provider?"
	}

	test("delete names a draft that does not exist and exits non-zero") {
		coEvery { manageTl.deleteDraft("internal") } returns ConfigurationError.draftNotFound("internal").left()

		val result = Omnisign().test(listOf("config", "tl", "build", "delete", "internal"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No TL draft named 'internal' found"
	}

	test("tsp remove names a provider the draft does not have, and exits non-zero") {
		coEvery { manageTl.removeTsp("internal", "Missing TSP") } returns
			ConfigurationError.tspNotFound("Missing TSP", "internal").left()

		val result = Omnisign().test(listOf("config", "tl", "build", "tsp", "remove", "internal", "Missing TSP"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No TSP named 'Missing TSP' in draft 'internal'"
	}

	test("service add names a provider the draft does not have, and exits non-zero") {
		coEvery { manageTl.upsertService("internal", "Missing TSP", any()) } returns
			ConfigurationError.tspNotFound("Missing TSP", "internal").left()

		val result = Omnisign().test(
			listOf(
				"config", "tl", "build", "service", "add", "internal", "Missing TSP",
				"--name", "Internal CA",
				"--type-id", "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
				"--status", "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
				"--cert", certificate.absolutePath,
			),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "No TSP named 'Missing TSP' in draft 'internal'"
	}

	test("service remove names a service the provider does not have, and exits non-zero") {
		coEvery { manageTl.removeService("internal", "OmniSign Test TSP", "Missing") } returns
			ConfigurationError.serviceNotFound("Missing", "OmniSign Test TSP").left()

		val result = Omnisign().test(
			listOf("config", "tl", "build", "service", "remove", "internal", "OmniSign Test TSP", "Missing"),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "No service named 'Missing' in TSP 'OmniSign Test TSP'"
	}
})
