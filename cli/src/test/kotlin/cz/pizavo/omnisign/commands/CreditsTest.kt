package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.api.model.responses.CreditsResponse
import cz.pizavo.omnisign.legal.ThirdPartyCreditsReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Behavioral tests for the [Credits] CLI command.
 *
 * The components fed to the mock are the ones really packaged, read once through a live
 * [ThirdPartyCreditsReader], so the rendering is exercised against the data that ships rather
 * than against a hand-written sample that could quietly diverge from it.
 */
class CreditsTest : FunSpec({

	val packagedComponents = ThirdPartyCreditsReader().read("cli")
	val creditsReader: ThirdPartyCreditsReader = mockk()
	val json = Json { ignoreUnknownKeys = true }

	extension(
		KoinExtension(
			module { single { creditsReader } },
			mode = KoinLifecycleMode.Test
		)
	)

	test("lists the packaged components grouped by licence") {
		every { creditsReader.read("cli") } returns packagedComponents

		val result = Omnisign().test(listOf("credits"))

		result.statusCode shouldBe 0
		result.output shouldContain "THIRD-PARTY COMPONENTS"
		result.output shouldContain "EU DSS (Digital Signature Services)"
		result.output shouldContain "GNU Lesser General Public License"
		result.output shouldContain "LGPL-2.1.txt"
		result.output shouldContain "THIRD-PARTY.md"
	}

	test("--json emits the same document the server's credits endpoint returns") {
		every { creditsReader.read("cli") } returns packagedComponents

		val result = Omnisign().test(listOf("--json", "credits"))

		result.statusCode shouldBe 0
		val body = json.decodeFromString<CreditsResponse>(result.output)
		body.components.shouldNotBeEmpty()
		body.components.map { it.name } shouldContain "EU DSS (Digital Signature Services)"
		body.license shouldBe "AGPL-3.0-or-later"
		body.source shouldBe "https://github.com/pizavo/omnisign"
		body.poweredBy shouldBe "OmniSign"
	}

	test("a package assembled without its credits data fails loudly") {
		every { creditsReader.read("cli") } returns emptyList()

		val result = Omnisign().test(listOf("credits"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No credits data found"
	}
})
