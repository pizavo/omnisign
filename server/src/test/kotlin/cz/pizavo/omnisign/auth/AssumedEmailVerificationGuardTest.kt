package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.OidcProviderConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [requireAssumableEmailVerification], the startup refusal of
 * `assumeEmailVerified` on Microsoft Entra ID's multi-tenant endpoints.
 */
class AssumedEmailVerificationGuardTest : FunSpec({

    /** A provider named `entra` that does or does not assume its addresses verified. */
    fun provider(assumeEmailVerified: Boolean) = OidcProviderConfig(
        name = "entra",
        clientId = "id",
        discoveryUrl = "https://login.example/.well-known/openid-configuration",
        allowedEmailDomains = listOf("contoso.com"),
        assumeEmailVerified = assumeEmailVerified,
    )

    /** A discovery document that declares [issuer]. */
    fun discovery(issuer: String) = OidcDiscoveryDocument(
        issuer = issuer,
        authorizationEndpoint = "https://login.example/authorize",
        tokenEndpoint = "https://login.example/token",
        jwksUri = "https://login.example/keys",
    )

    test("refuses assumeEmailVerified on Entra ID's multi-tenant documents and names the provider, issuer and remedy") {
        listOf(
            "https://login.microsoftonline.com/{tenantid}/v2.0",
            "https://sts.windows.net/{tenantid}/",
            "https://login.microsoftonline.us/{TenantId}/v2.0",
        ).forEach { issuer ->
            withClue(issuer) {
                val ex = shouldThrow<IllegalArgumentException> {
                    requireAssumableEmailVerification(provider(assumeEmailVerified = true), discovery(issuer))
                }
                ex.message!! shouldContain "'entra'"
                ex.message!! shouldContain issuer
                ex.message!! shouldContain "xms_edov"
            }
        }
    }

    test("accepts a multi-tenant Entra document when the provider does not assume verification") {
        requireAssumableEmailVerification(
            provider(assumeEmailVerified = false),
            discovery("https://login.microsoftonline.com/{tenantid}/v2.0"),
        )
    }

    test("accepts assumeEmailVerified on a single tenant's, the personal accounts' or another IdP's document") {
        listOf(
            "https://login.microsoftonline.com/31537af4-6d77-4bb9-a681-d2394888ea26/v2.0",
            "https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0",
            "https://idp.example/realms/common",
        ).forEach { issuer ->
            withClue(issuer) {
                requireAssumableEmailVerification(provider(assumeEmailVerified = true), discovery(issuer))
            }
        }
    }
})
