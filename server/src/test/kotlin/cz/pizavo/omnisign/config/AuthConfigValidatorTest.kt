package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [validateAuthConfig] — startup-time misconfiguration checks for OIDC
 * provider blocks.
 */
class AuthConfigValidatorTest : FunSpec({

    fun oidc(
        name: String,
        allowedEmailDomains: List<String>,
        requiredClaims: Map<String, List<String>>? = null,
    ) = OidcProviderConfig(
        name = name,
        clientId = "id",
        discoveryUrl = "https://idp.example/.well-known/openid-configuration",
        allowedEmailDomains = allowedEmailDomains,
        requiredClaims = requiredClaims,
    )

    test("accepts a null AuthConfig (auth disabled)") {
        validateAuthConfig(null)
    }

    test("accepts an AuthConfig with no providers") {
        validateAuthConfig(AuthConfig(providers = emptyList()))
    }

    test("accepts an OIDC provider with the wildcard allowedEmailDomains") {
        validateAuthConfig(AuthConfig(providers = listOf(oidc("g", listOf("*")))))
    }

    test("accepts an OIDC provider with a concrete allowedEmailDomains list") {
        validateAuthConfig(AuthConfig(providers = listOf(oidc("g", listOf("contoso.com", "fabrikam.com")))))
    }

    test("rejects an OIDC provider with an empty allowedEmailDomains list and names the provider") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateAuthConfig(AuthConfig(providers = listOf(oidc("acme-idp", emptyList()))))
        }
        ex.message!! shouldContain "acme-idp"
        ex.message!! shouldContain "allowedEmailDomains"
        ex.message!! shouldContain "[\"*\"]"
    }

    test("rejects a requiredClaims entry with an empty accepted-values list and names the offending key") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateAuthConfig(
                AuthConfig(
                    providers = listOf(
                        oidc(
                            name = "eduid",
                            allowedEmailDomains = listOf("*"),
                            requiredClaims = mapOf("schac_home_organization" to emptyList()),
                        ),
                    ),
                ),
            )
        }
        ex.message!! shouldContain "eduid"
        ex.message!! shouldContain "schac_home_organization"
        ex.message!! shouldContain "no accepted values"
    }

    test("validates every OIDC provider in the list (failure on the second provider surfaces)") {
        val good = oidc("good", listOf("*"))
        val bad = oidc("bad", emptyList())
        val ex = shouldThrow<IllegalArgumentException> {
            validateAuthConfig(AuthConfig(providers = listOf(good, bad)))
        }
        ex.message!! shouldContain "bad"
    }

    test("accepts an empty allowedRedirectUris (no browser hand-off configured)") {
        validateAuthConfig(AuthConfig(providers = listOf(oidc("g", listOf("*"))), allowedRedirectUris = emptyList()))
    }

    test("accepts an https allowedRedirectUris entry") {
        validateAuthConfig(
            AuthConfig(
                providers = listOf(oidc("g", listOf("*"))),
                allowedRedirectUris = listOf("https://omnisign.example.com/"),
            ),
        )
    }

    test("accepts an http allowedRedirectUris entry on a loopback host (local development)") {
        validateAuthConfig(
            AuthConfig(
                providers = listOf(oidc("g", listOf("*"))),
                allowedRedirectUris = listOf("http://localhost:8080/", "http://127.0.0.1:3000/app"),
            ),
        )
    }

    test("rejects a non-absolute allowedRedirectUris entry") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateAuthConfig(
                AuthConfig(
                    providers = listOf(oidc("g", listOf("*"))),
                    allowedRedirectUris = listOf("/app"),
                ),
            )
        }
        ex.message!! shouldContain "/app"
        ex.message!! shouldContain "absolute URL"
    }

    test("rejects a plain-http allowedRedirectUris entry on a non-loopback host") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateAuthConfig(
                AuthConfig(
                    providers = listOf(oidc("g", listOf("*"))),
                    allowedRedirectUris = listOf("http://omnisign.example.com/"),
                ),
            )
        }
        ex.message!! shouldContain "omnisign.example.com"
        ex.message!! shouldContain "https"
    }

    test("rejects a non-http(s) scheme in an allowedRedirectUris entry") {
        shouldThrow<IllegalArgumentException> {
            validateAuthConfig(
                AuthConfig(
                    providers = listOf(oidc("g", listOf("*"))),
                    allowedRedirectUris = listOf("ftp://omnisign.example.com/"),
                ),
            )
        }
    }
})
