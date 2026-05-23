package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.sensitive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 64-byte test secret used in tests that construct a [HeaderInjectionProviderConfig].
 * Length matches the production minimum so deserialization succeeds; value is fixed for
 * deterministic test output.
 */
private const val HEADER_INJECTION_TEST_SECRET = "test-shared-secret-padded-to-at-least-64-bytes-for-test-fixtures!"

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

    test("deserializes a header-injection provider from YAML") {
        val yaml = """
            auth:
              providers:
                - type: header-injection
                  name: shibboleth
                  userHeader: "X-Remote-User"
                  emailHeader: "X-Shib-Mail"
                  displayNameHeader: "X-Shib-Cn"
                  sharedSecret: "$HEADER_INJECTION_TEST_SECRET"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val provider = config.auth?.providers?.first()
        provider.shouldBeInstanceOf<HeaderInjectionProviderConfig>()
        provider.name shouldBe "shibboleth"
        provider.userHeader shouldBe "X-Remote-User"
        provider.sharedSecret shouldBe HEADER_INJECTION_TEST_SECRET.sensitive()
        provider.sharedSecretHeader shouldBe "X-Header-Injection-Token"
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
                  allowedEmailDomains: ["*"]
                - type: header-injection
                  name: eduid
                  userHeader: "REMOTE_USER"
                  sharedSecret: "$HEADER_INJECTION_TEST_SECRET"
        """.trimIndent()

        val config = loader.loadFromString(yaml)
        val providers = config.auth!!.providers
        providers.size shouldBe 2
        providers[0].shouldBeInstanceOf<OidcProviderConfig>()
        providers[1].shouldBeInstanceOf<HeaderInjectionProviderConfig>()
    }

    test("header-injection provider rejects sharedSecret shorter than the minimum") {
        val yaml = """
            auth:
              providers:
                - type: header-injection
                  name: shib
                  sharedSecret: "too-short"
        """.trimIndent()

        shouldThrow<Exception> { loader.loadFromString(yaml) }
    }

    test("header-injection provider requires sharedSecret to be present") {
        val yaml = """
            auth:
              providers:
                - type: header-injection
                  name: shib
        """.trimIndent()

        shouldThrow<Exception> { loader.loadFromString(yaml) }
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
