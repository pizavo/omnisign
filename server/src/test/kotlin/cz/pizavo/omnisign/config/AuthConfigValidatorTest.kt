package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.sensitive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * 64-byte filler for [HeaderInjectionProviderConfig.sharedSecret] in tests that combine
 * mixed providers; length matches the production minimum so construction succeeds.
 */
private const val HEADER_INJECTION_TEST_SECRET = "test-shared-secret-padded-to-at-least-64-bytes-for-test-fixtures!"

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

    test("skips HeaderInjectionProviderConfig (no email/claims filter to validate)") {
        val mixed = AuthConfig(
            providers = listOf(
                oidc("o", listOf("*")),
                HeaderInjectionProviderConfig(
                    name = "shib",
                    sharedSecret = HEADER_INJECTION_TEST_SECRET.sensitive(),
                ),
            ),
        )
        validateAuthConfig(mixed)
    }
})
