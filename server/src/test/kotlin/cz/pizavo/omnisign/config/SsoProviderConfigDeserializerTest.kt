package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [ServerConfigLoader] focusing on the SSO provider YAML deserialization
 * via [SsoProviderConfigDeserializer].
 */
class SsoProviderConfigDeserializerTest : FunSpec({

    val loader = ServerConfigLoader()

    test("deserializes an oidc provider from YAML") {
        val yaml = """
            requireLogin: true
            auth:
              providers:
                - type: oidc
                  name: google
                  preset: GOOGLE
                  clientId: "my-client-id"
                  clientSecret: "my-client-secret"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val providers = config.auth?.providers
        providers?.size shouldBe 1
        val provider = providers?.first()
        provider.shouldBeInstanceOf<OidcProviderConfig>()
        provider.name shouldBe "google"
        provider.clientId shouldBe "my-client-id"
        provider.preset shouldBe SsoProviderPreset.GOOGLE
    }

    test("deserializes a header-injection provider from YAML") {
        val yaml = """
            auth:
              providers:
                - type: header-injection
                  name: shibboleth
                  userHeader: "X-Remote-User"
                  emailHeader: "X-Shib-Mail"
                  displayNameHeader: "X-Shib-Cn"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<HeaderInjectionProviderConfig>()
        provider.name shouldBe "shibboleth"
        provider.userHeader shouldBe "X-Remote-User"
    }

    test("deserializes multiple mixed providers") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: microsoft
                  preset: MICROSOFT
                  tenantId: "common"
                  clientId: "ms-id"
                  clientSecret: "ms-secret"
                - type: header-injection
                  name: eduid
                  userHeader: "REMOTE_USER"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val providers = config.auth!!.providers
        providers.size shouldBe 2
        providers[0].shouldBeInstanceOf<OidcProviderConfig>()
        providers[1].shouldBeInstanceOf<HeaderInjectionProviderConfig>()    }

    test("throws on unknown provider type") {
        val yaml = """
            auth:
              providers:
                - type: saml
                  name: unknown
        """.trimIndent()

        shouldThrow<Exception> { loader.loadFromString(yaml) }
    }

    test("deserializes allowedEmailDomains on an oidc provider") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: microsoft
                  preset: MICROSOFT
                  tenantId: "common"
                  clientId: "ms-id"
                  clientSecret: "ms-secret"
                  allowedEmailDomains:
                    - "contoso.com"
                    - "fabrikam.com"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<OidcProviderConfig>()
        provider.allowedEmailDomains shouldBe listOf("contoso.com", "fabrikam.com")
    }

    test("allowedEmailDomains defaults to null when not specified") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: google
                  preset: GOOGLE
                  clientId: "id"
                  clientSecret: "secret"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<OidcProviderConfig>()
        provider.allowedEmailDomains shouldBe null
    }

    test("deserializes requiredClaims on an oidc provider") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: eduid
                  preset: EDUID_CZ
                  clientId: "eduid-id"
                  clientSecret: "eduid-secret"
                  requiredClaims:
                    schac_home_organization:
                      - "osu.cz"
                    eduperson_scoped_affiliation:
                      - "staff@osu.cz"
                      - "faculty@osu.cz"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<OidcProviderConfig>()
        provider.requiredClaims shouldBe mapOf(
            "schac_home_organization" to listOf("osu.cz"),
            "eduperson_scoped_affiliation" to listOf("staff@osu.cz", "faculty@osu.cz"),
        )
    }

    test("requiredClaims defaults to null when not specified") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: google
                  preset: GOOGLE
                  clientId: "id"
                  clientSecret: "secret"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<OidcProviderConfig>()
        provider.requiredClaims shouldBe null
    }

    test("auth is null when not specified") {
        val yaml = """
            host: "127.0.0.1"
            port: 8080
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        config.auth shouldBe null
    }
})
