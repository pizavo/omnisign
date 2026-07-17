package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [validateCorsConfig].
 *
 * Pins the operator-facing contract: missing `cors:` block and empty `allowedOrigins`
 * list both surface as startup failures with messages that name the field and offer
 * the wildcard escape hatch, and the wildcard may not be combined with credentialed CORS.
 */
class CorsConfigValidatorTest : FunSpec({

    test("rejects a null CorsConfig with a message naming the field") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateCorsConfig(null)
        }
        ex.message!! shouldContain "cors.allowedOrigins"
        ex.message!! shouldContain "[\"*\"]"
    }

    test("rejects a CorsConfig with an empty allowedOrigins list") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateCorsConfig(CorsConfig(allowedOrigins = emptyList()))
        }
        ex.message!! shouldContain "must not be empty"
        ex.message!! shouldContain "[\"*\"]"
    }

    test("accepts the wildcard \"*\" allowedOrigins") {
        val result = validateCorsConfig(CorsConfig(allowedOrigins = listOf("*")))
        result.allowedOrigins shouldBe listOf("*")
    }

    test("accepts a concrete allowedOrigins list and returns it verbatim") {
        val origins = listOf("https://omnisign.example.com", "https://staging.example.com")
        val result = validateCorsConfig(CorsConfig(allowedOrigins = origins))
        result.allowedOrigins shouldBe origins
    }

    test("rejects allowCredentials combined with the \"*\" wildcard") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateCorsConfig(CorsConfig(allowedOrigins = listOf("*"), allowCredentials = true))
        }
        ex.message!! shouldContain "cors.allowCredentials"
        ex.message!! shouldContain "any site the user visits"
    }

    test("rejects allowCredentials when \"*\" appears alongside concrete origins") {
        shouldThrow<IllegalArgumentException> {
            validateCorsConfig(
                CorsConfig(
                    allowedOrigins = listOf("https://omnisign.example.com", "*"),
                    allowCredentials = true,
                ),
            )
        }
    }

    test("accepts allowCredentials with a concrete allowedOrigins list") {
        val result = validateCorsConfig(
            CorsConfig(allowedOrigins = listOf("https://omnisign.example.com"), allowCredentials = true),
        )
        result.allowCredentials shouldBe true
    }

    test("accepts the \"*\" wildcard while credentials stay off") {
        val result = validateCorsConfig(CorsConfig(allowedOrigins = listOf("*"), allowCredentials = false))
        result.allowedOrigins shouldBe listOf("*")
        result.allowCredentials shouldBe false
    }
})
