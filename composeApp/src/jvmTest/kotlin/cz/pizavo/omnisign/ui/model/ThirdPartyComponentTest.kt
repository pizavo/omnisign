package cz.pizavo.omnisign.ui.model

import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.Json

/**
 * Pins the contract between the root `:generateThirdPartyNotices` task and [ThirdPartyComponent].
 *
 * The Credits dialog reads the generated credits list at runtime from the packaged Compose
 * resource, so a change to the generator's output shape would otherwise only surface as an empty
 * dialog on a user's machine. These tests parse the very file that ships and assert the fields the
 * dialog depends on are present, including the licence attributions that the weak-copyleft
 * dependencies legally require the application to display.
 */
class ThirdPartyComponentTest : FunSpec({

    val resourcePath = "composeResources/omnisign.composeapp.generated.resources/files/third-party-credits.json"

    fun loadComponents(): List<ThirdPartyComponent> {
        val stream = checkNotNull(
            ThirdPartyComponentTest::class.java.classLoader.getResourceAsStream(resourcePath),
        ) { "Generated credits resource is missing: $resourcePath" }
        val json = stream.use { it.readBytes().decodeToString() }
        return Json { ignoreUnknownKeys = true }.decodeFromString(json)
    }

    test("the packaged credits resource deserializes") {
        loadComponents().size shouldBeGreaterThan 40
    }

    test("every component carries the fields the dialog renders") {
        loadComponents().forEach { component ->
            component.name.shouldNotBeBlank()
            component.licenseId.shouldNotBeBlank()
            component.licenseName.shouldNotBeBlank()
            component.licenseText.shouldNotBeBlank()
            component.artifacts shouldBeGreaterThan 0
        }
    }

    test("every component is tagged with at least one known surface") {
        val known = setOf("cli", "server", "desktop", "web")
        loadComponents().forEach { component ->
            component.surfaces.shouldNotBeEmpty()
            component.surfaces.forEach { it shouldBeIn known }
        }
    }

    test("the desktop and web surfaces select genuinely different sets") {
        val components = loadComponents()
        val desktop = components.filter { "desktop" in it.surfaces }.map { it.name }
        val web = components.filter { "web" in it.surfaces }.map { it.name }

        desktop.shouldNotBeEmpty()
        web.shouldNotBeEmpty()
        desktop shouldContain "EU DSS (Digital Signature Services)"
        web shouldNotContain "EU DSS (Digital Signature Services)"
    }

    test("the web surface credits the npm packages the browser bundle ships") {
        val web = loadComponents().filter { "web" in it.surfaces }
        val mupdf = web.singleOrNull { it.name == "MuPDF" }
        mupdf.shouldNotBeNull()
        mupdf.licenseId shouldBe "AGPL-3.0-or-later"
        mupdf.copyright.shouldNotBeNull().shouldNotBeBlank()
        mupdf.homepage.shouldNotBeNull().shouldNotBeBlank()

        loadComponents().filter { "desktop" in it.surfaces }.map { it.name } shouldNotContain "MuPDF"
    }

    test("DSS is listed under the LGPL with its copyright and source location") {
        val dss = loadComponents().singleOrNull { it.name.startsWith("EU DSS") }
        dss.shouldNotBeNull()
        dss.licenseId shouldBe "LGPL-2.1-or-later"
        dss.licenseText shouldBe "LGPL-2.1.txt"
        dss.copyright.shouldNotBeNull().shouldNotBeBlank()
        dss.homepage.shouldNotBeNull().shouldNotBeBlank()
    }

    test("every weak-copyleft component states a copyright and a source location") {
        val copyleft = loadComponents().filter {
            it.licenseId.startsWith("LGPL") || it.licenseId == "MPL-2.0"
        }
        copyleft.map { it.licenseId }.distinct() shouldContain "LGPL-2.1-or-later"
        copyleft.forEach { component ->
            component.copyright.shouldNotBeNull().shouldNotBeBlank()
            component.homepage.shouldNotBeNull().shouldNotBeBlank()
        }
    }
})
