package cz.pizavo.omnisign.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [resolveServerUrl], pinning the precedence between a runtime
 * `web-config.json` override and the build-time fallback.
 */
class ServerUrlResolverTest : FunSpec({
    val default = "https://build-time.example:18443"

    test("null config falls back to the build-time default") {
        resolveServerUrl(null, default) shouldBe ResolvedServerUrl(default, malformedConfig = false)
    }

    test("blank config falls back to the build-time default") {
        resolveServerUrl("   ", default) shouldBe ResolvedServerUrl(default, malformedConfig = false)
    }

    test("a non-blank serverUrl overrides the build-time default") {
        resolveServerUrl("""{"serverUrl":"https://omnisign.pizavo.cz:18443"}""", default) shouldBe
            ResolvedServerUrl("https://omnisign.pizavo.cz:18443", malformedConfig = false)
    }

    test("a blank serverUrl falls back to the build-time default") {
        resolveServerUrl("""{"serverUrl":""}""", default) shouldBe
            ResolvedServerUrl(default, malformedConfig = false)
    }

    test("a null serverUrl falls back to the build-time default") {
        resolveServerUrl("""{"serverUrl":null}""", default) shouldBe
            ResolvedServerUrl(default, malformedConfig = false)
    }

    test("an empty JSON object falls back to the build-time default") {
        resolveServerUrl("{}", default) shouldBe ResolvedServerUrl(default, malformedConfig = false)
    }

    test("unknown fields are ignored") {
        resolveServerUrl("""{"serverUrl":"https://x:1","future":true}""", default) shouldBe
            ResolvedServerUrl("https://x:1", malformedConfig = false)
    }

    test("malformed JSON falls back and is flagged as malformed") {
        resolveServerUrl("{ not json", default) shouldBe ResolvedServerUrl(default, malformedConfig = true)
    }
})
