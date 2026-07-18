package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [ServerConfigLoader] focusing on the SSO provider YAML deserialization
 * via [SsoProviderConfigDeserializer].
 */
class SsoProviderConfigDeserializerTest : FunSpec({

    val loader = ServerConfigLoader()

    test("deserializes an oidc provider from YAML") {
        val yaml = """
            auth:
              enabled: true
              providers:
                - type: oidc
                  name: google
                  preset: GOOGLE
                  clientId: "my-client-id"
                  allowedEmailDomains: ["*"]
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

    test("deserializes multiple providers") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: microsoft
                  preset: MICROSOFT
                  tenantId: "common"
                  clientId: "ms-id"
                  allowedEmailDomains: ["*"]
                - type: oidc
                  name: google
                  preset: GOOGLE
                  clientId: "g-id"
                  allowedEmailDomains: ["*"]
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val providers = config.auth!!.providers
        providers.size shouldBe 2
        providers[0].shouldBeInstanceOf<OidcProviderConfig>()
        providers[1].shouldBeInstanceOf<OidcProviderConfig>()
    }

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
                  allowedEmailDomains:
                    - "contoso.com"
                    - "fabrikam.com"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<OidcProviderConfig>()
        provider.allowedEmailDomains shouldBe listOf("contoso.com", "fabrikam.com")
    }

    test("oidc provider YAML missing allowedEmailDomains is rejected at parse time") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: google
                  preset: GOOGLE
                  clientId: "id"
        """.trimIndent()

        val ex = shouldThrow<Exception> { loader.loadFromString(yaml) }
        ex.message.shouldNotBeNull()
        ex.message!! shouldContain "allowedEmailDomains"
    }

    test("deserializes requiredClaims on an oidc provider") {
        val yaml = """
            auth:
              providers:
                - type: oidc
                  name: eduid
                  preset: EDUID_CZ
                  clientId: "eduid-id"
                  allowedEmailDomains: ["*"]
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
                  allowedEmailDomains: ["*"]
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<OidcProviderConfig>()
        provider.requiredClaims shouldBe null
    }

    test("auth is null when not specified") {
        val yaml = """
            listen:
              host: "127.0.0.1"
              port: 8080
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        config.auth shouldBe null
    }
})
