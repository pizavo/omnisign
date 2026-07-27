package cz.pizavo.omnisign.commands.config.tl.build.tsp

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.koin.dsl.module

/**
 * Behavioral tests for the non-interactive `tl build tsp add` command, whose only real decision is
 * how to assemble the address options ETSI TS 119612 requires of every provider.
 *
 * A draft is routinely built up across several invocations, so supplying no address at all must
 * leave the field genuinely unset rather than storing a hollow one — otherwise the compiler's
 * "which fields are missing" report would describe an address that exists but says nothing.
 */
class TrustedListBuildTspAddTest : FunSpec({

	val manageTl: ManageTrustedListsUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageTl } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageTl) }

	/** Run `tl build tsp add` with [args] and return the TSP it would persist. */
	suspend fun addTsp(vararg args: String): TrustServiceProviderDraft {
		val tsp = slot<TrustServiceProviderDraft>()
		coEvery { manageTl.upsertTsp(any(), capture(tsp)) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "tl", "build", "tsp", "add", "draft", *args))

		result.statusCode shouldBe 0
		return tsp.captured
	}

	test("carries the name, trade name and information URL") {
		val tsp = addTsp(
			"--name", "OmniSign Test TSP",
			"--trade-name", "Trading As",
			"--info-url", "https://tsp.omnisign.test",
		)

		tsp.name shouldBe "OmniSign Test TSP"
		tsp.tradeName shouldBe "Trading As"
		tsp.infoUrl shouldBe "https://tsp.omnisign.test"
	}

	test("leaves the address unset when no address option is given") {
		val tsp = addTsp("--name", "TSP")

		tsp.address shouldBe null
	}

	test("assembles the address from the supplied options") {
		val tsp = addTsp(
			"--name", "TSP",
			"--street", "Technicka 2",
			"--locality", "Praha",
			"--country", "CZ",
			"--state", "Praha",
			"--postal-code", "16000",
			"--electronic-address", "mailto:tl@omnisign.test",
		)

		val address = tsp.address.shouldNotBeNull()
		address.streetAddress shouldBe "Technicka 2"
		address.locality shouldBe "Praha"
		address.countryName shouldBe "CZ"
		address.stateOrProvince shouldBe "Praha"
		address.postalCode shouldBe "16000"
		address.electronicAddress shouldBe "mailto:tl@omnisign.test"
	}

	test("keeps a partially supplied address so the compiler can name what is still missing") {
		val tsp = addTsp("--name", "TSP", "--street", "Technicka 2")

		val address = tsp.address.shouldNotBeNull()
		address.streetAddress shouldBe "Technicka 2"
		address.locality shouldBe ""
		address.countryName shouldBe ""
		address.electronicAddress shouldBe ""
	}

	test("leaves the optional address parts unset when they are not given") {
		val tsp = addTsp(
			"--name", "TSP",
			"--street", "Technicka 2",
			"--locality", "Praha",
			"--country", "CZ",
			"--electronic-address", "mailto:tl@omnisign.test",
		)

		val address = tsp.address.shouldNotBeNull()
		address.stateOrProvince shouldBe null
		address.postalCode shouldBe null
	}

	test("reports a failure on stderr") {
		coEvery { manageTl.upsertTsp(any(), any()) } returns
			ConfigurationError.loadFailed(details = "no draft 'draft'").left()

		val result = Omnisign().test(
			listOf("config", "tl", "build", "tsp", "add", "draft", "--name", "TSP"),
		)

		result.stderr shouldContain "Details: no draft 'draft'"
	}
})
