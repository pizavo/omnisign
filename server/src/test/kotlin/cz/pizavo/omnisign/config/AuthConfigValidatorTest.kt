package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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

    test("accepts an http allowedRedirectUris entry on the IPv6 loopback or an upper-case localhost") {
        validateAuthConfig(
            AuthConfig(
                providers = listOf(oidc("g", listOf("*"))),
                allowedRedirectUris = listOf("http://[::1]:8080/", "http://LOCALHOST:3000/app"),
            ),
        )
    }

    fun microsoft(tenantId: String, assumeEmailVerified: Boolean) = OidcProviderConfig(
        name = "microsoft",
        preset = SsoProviderPreset.MICROSOFT,
        tenantId = tenantId,
        clientId = "id",
        allowedEmailDomains = listOf("contoso.com"),
        assumeEmailVerified = assumeEmailVerified,
    )

    test("rejects assumeEmailVerified on a multi-tenant Microsoft provider and names it and the remedy") {
        listOf("common", "Organizations").forEach { tenant ->
            val ex = shouldThrow<IllegalArgumentException> {
                validateAuthConfig(AuthConfig(providers = listOf(microsoft(tenant, assumeEmailVerified = true))))
            }
            ex.message!! shouldContain "'microsoft'"
            ex.message!! shouldContain tenant
            ex.message!! shouldContain "xms_edov"
        }
    }

    test("accepts assumeEmailVerified on a single-tenant or consumer Microsoft provider") {
        listOf("contoso.onmicrosoft.com", "00000000-0000-0000-0000-000000000001", "consumers").forEach { tenant ->
            validateAuthConfig(AuthConfig(providers = listOf(microsoft(tenant, assumeEmailVerified = true))))
        }
    }

    test("accepts a multi-tenant Microsoft provider that does not assume verification") {
        validateAuthConfig(AuthConfig(providers = listOf(microsoft("common", assumeEmailVerified = false))))
    }

    test("accepts assumeEmailVerified on a provider that is not Microsoft") {
        validateAuthConfig(
            AuthConfig(providers = listOf(oidc("keycloak", listOf("contoso.com")).copy(assumeEmailVerified = true))),
        )
    }

    /** A provider that reaches its IdP through the explicit [discoveryUrl], assuming verification. */
    fun assumingAt(discoveryUrl: String, preset: SsoProviderPreset?) = OidcProviderConfig(
        name = "entra",
        preset = preset,
        clientId = "id",
        discoveryUrl = discoveryUrl,
        allowedEmailDomains = listOf("contoso.com"),
        assumeEmailVerified = true,
    )

    test("rejects assumeEmailVerified on a discoveryUrl that reaches a multi-tenant Microsoft endpoint") {
        listOf(
            assumingAt(
                "https://login.sovereign-cloud.example/organizations/v2.0/.well-known/openid-configuration",
                SsoProviderPreset.MICROSOFT,
            ),
            assumingAt("https://login.microsoftonline.us/organizations/v2.0/.well-known/openid-configuration", null),
            assumingAt("https://login.microsoftonline.com/common/v2.0/.well-known/openid-configuration", null),
            assumingAt("https://LOGIN.chinacloudapi.cn/Organizations/v2.0/.well-known/openid-configuration", null),
            microsoft("contoso.onmicrosoft.com", assumeEmailVerified = true).copy(
                discoveryUrl = "https://login.microsoftonline.com/common/v2.0/.well-known/openid-configuration",
            ),
        ).forEach { provider ->
            val ex = shouldThrow<IllegalArgumentException> {
                validateAuthConfig(AuthConfig(providers = listOf(provider)))
            }
            ex.message!! shouldContain provider.discoveryUrl!!
            ex.message!! shouldContain "xms_edov"
        }
    }

    test("rejects assumeEmailVerified on a discovery URL spelled to reach a multi-tenant Microsoft endpoint") {
        val backslash = '\\'
        val wellKnown = "v2.0/.well-known/openid-configuration"
        listOf(
            assumingAt("https://login.microsoftonline.com/organizations/$wellKnown\n", null),
            assumingAt("https://login.microsoftonline.com/common/$wellKnown?client={id}", null),
            assumingAt("https://login.microsoftonline.com/contoso.onmicrosoft.com/../common/$wellKnown", null),
            assumingAt("https://login.microsoftonline.com/contoso/%2e%2e/%6Frganizations/$wellKnown", null),
            assumingAt("https://login.microsoftonline.com/contoso%2F..%2Fcommon/$wellKnown", null),
            assumingAt("https://login.microsoftonline.com/contoso$backslash..${backslash}common/$wellKnown", null),
            assumingAt("https://login.microsoftonline.com/organizations;v=1/$wellKnown", null),
            assumingAt("https://Login.MicrosoftOnline.com./common/$wellKnown", null),
            assumingAt("https://login.microsoftonline.com/common%20/$wellKnown", null),
            microsoft("contoso.onmicrosoft.com/../organizations", assumeEmailVerified = true),
            OidcProviderConfig(
                name = "auth0",
                preset = SsoProviderPreset.AUTH0,
                tenantId = "login.microsoftonline.com/organizations/v2.0",
                clientId = "id",
                allowedEmailDomains = listOf("contoso.com"),
                assumeEmailVerified = true,
            ),
        ).forEach { provider ->
            withClue(provider.discoveryUrl ?: provider.tenantId) {
                val ex = shouldThrow<IllegalArgumentException> {
                    validateAuthConfig(AuthConfig(providers = listOf(provider)))
                }
                ex.message!! shouldContain "'${provider.name}'"
                ex.message!! shouldContain "xms_edov"
            }
        }
    }

    test("accepts assumeEmailVerified on a discoveryUrl for one Microsoft tenant or another IdP") {
        listOf(
            assumingAt(
                "https://login.microsoftonline.com/contoso.onmicrosoft.com/v2.0/.well-known/openid-configuration",
                SsoProviderPreset.MICROSOFT,
            ),
            assumingAt("https://idp.example/organizations/.well-known/openid-configuration", null),
            microsoft("common", assumeEmailVerified = true).copy(
                discoveryUrl = "https://login.microsoftonline.com/contoso.onmicrosoft.com/v2.0/.well-known/openid-configuration",
            ),
            OidcProviderConfig(
                name = "keycloak",
                preset = SsoProviderPreset.KEYCLOAK,
                tenantId = "idp.example/common",
                clientId = "id",
                allowedEmailDomains = listOf("contoso.com"),
                assumeEmailVerified = true,
            ),
        ).forEach { provider ->
            withClue(provider.discoveryUrl ?: provider.tenantId) {
                validateAuthConfig(AuthConfig(providers = listOf(provider)))
            }
        }
    }
})
