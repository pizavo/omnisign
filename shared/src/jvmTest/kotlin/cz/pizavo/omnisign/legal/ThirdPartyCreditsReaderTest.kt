package cz.pizavo.omnisign.legal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Pins the contract between the root `:generateThirdPartyNotices` task and the copy of its
 * credits list that the CLI and the server read from the JVM classpath.
 *
 * `omnisign credits` and `GET /api/v1/credits` are the only place either headless package
 * credits its dependencies to a user, so a resource that failed to ship, or whose shape drifted
 * from [ThirdPartyComponent], would show up as a silently empty listing rather than as a build
 * failure. These tests load the very resource that is packaged.
 *
 * The Compose-resource copy the desktop and web applications read is covered separately by
 * `ThirdPartyComponentTest` in `composeApp`.
 */
class ThirdPartyCreditsReaderTest : FunSpec({

    val reader = ThirdPartyCreditsReader()

    test("the packaged credits resource is on the classpath") {
        ThirdPartyCreditsReader::class.java
            .getResourceAsStream(ThirdPartyCreditsReader.CREDITS_RESOURCE)
            .shouldNotBeNull()
            .close()
    }

    test("both headless surfaces resolve a populated list") {
        reader.read("cli").shouldNotBeEmpty()
        reader.read("server").shouldNotBeEmpty()
    }

    test("a surface only ever returns components tagged with it") {
        listOf("cli", "server", "desktop", "web").forEach { surface ->
            reader.read(surface).forEach { it.surfaces shouldContain surface }
        }
    }

    test("an unknown surface resolves to nothing rather than to everything") {
        reader.read("android").shouldBeEmpty()
    }

    test("the surfaces select genuinely different sets") {
        val cli = reader.read("cli").map { it.name }
        val server = reader.read("server").map { it.name }

        cli shouldContain "EU DSS (Digital Signature Services)"
        server shouldContain "EU DSS (Digital Signature Services)"
        cli shouldContain "Clikt"
        server shouldNotContain "Clikt"
        cli shouldNotContain "MuPDF"
        server shouldNotContain "MuPDF"
    }

    test("every component the server publishes carries what an attribution needs") {
        reader.read("server").forEach { component ->
            component.name.shouldNotBeBlank()
            component.licenseId.shouldNotBeBlank()
            component.licenseName.shouldNotBeBlank()
            component.licenseText.shouldNotBeBlank()
        }
    }

    test("the weak-copyleft components state a copyright and a source location") {
        val copyleft = reader.read("cli").filter {
            it.licenseId.startsWith("LGPL") || it.licenseId == "MPL-2.0"
        }
        copyleft.shouldNotBeEmpty()
        copyleft.forEach { component ->
            component.copyright.shouldNotBeNull().shouldNotBeBlank()
            component.homepage.shouldNotBeNull().shouldNotBeBlank()
        }
    }

    test("DSS is credited under the LGPL on both headless surfaces") {
        listOf("cli", "server").forEach { surface ->
            val dss = reader.read(surface).singleOrNull { it.name.startsWith("EU DSS") }
            dss.shouldNotBeNull()
            dss.licenseId shouldBe "LGPL-2.1-or-later"
            dss.licenseText shouldBe "LGPL-2.1.txt"
        }
    }
})
