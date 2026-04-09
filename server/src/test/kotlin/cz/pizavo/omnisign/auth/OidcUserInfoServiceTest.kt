package cz.pizavo.omnisign.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unit tests for [OidcUserInfoService.toPrincipal] covering the multi-field fallback
 * chain for userId, email, and displayName.
 *
 * The [HttpClient] is created with a no-op [MockEngine] since these tests only exercise
 * the synchronous [OidcUserInfoService.toPrincipal] mapping logic, not the network-fetching [OidcUserInfoService.fetchRawClaims].
 */
class OidcUserInfoServiceTest : FunSpec({

    val service = OidcUserInfoService(HttpClient(MockEngine) { engine { addHandler { error("unused") } } })

    test("toPrincipal maps standard OIDC claims correctly") {
        val claims = buildJsonObject {
            put("sub", "user-abc-123")
            put("email", "user@example.com")
            put("name", "Alice Smith")
        }
        val principal = service.toPrincipal(claims, "google")

        principal.userId shouldBe "user-abc-123"
        principal.email shouldBe "user@example.com"
        principal.displayName shouldBe "Alice Smith"
        principal.providerName shouldBe "google"
    }

    test("toPrincipal falls back to 'id' when 'sub' is absent (GitHub numeric ID)") {
        val claims = buildJsonObject {
            put("id", "12345678")
            put("login", "octocat")
            put("email", "octocat@github.com")
            put("name", "The Octocat")
        }
        val principal = service.toPrincipal(claims, "github")

        principal.userId shouldBe "12345678"
    }

    test("toPrincipal falls back to 'login' when both 'sub' and 'id' are absent") {
        val claims = buildJsonObject {
            put("login", "octocat")
            put("email", "octocat@github.com")
        }
        val principal = service.toPrincipal(claims, "github")

        principal.userId shouldBe "octocat"
    }

    test("toPrincipal throws when no userId claim is present") {
        val claims = buildJsonObject {
            put("email", "user@example.com")
        }
        shouldThrow<IllegalStateException> {
            service.toPrincipal(claims, "unknown")
        }
    }

    test("toPrincipal falls back to 'login' for email when 'email' is absent (GitHub)") {
        val claims = buildJsonObject {
            put("sub", "12345678")
            put("login", "octocat")
        }
        val principal = service.toPrincipal(claims, "github")

        principal.email shouldBe "octocat"
    }

    test("toPrincipal throws when no email claim is present") {
        val claims = buildJsonObject {
            put("sub", "user-abc")
        }
        shouldThrow<IllegalStateException> {
            service.toPrincipal(claims, "noemail")
        }
    }

    test("toPrincipal falls back to 'preferred_username' for displayName when 'name' is absent") {
        val claims = buildJsonObject {
            put("sub", "u1")
            put("email", "u@example.com")
            put("preferred_username", "alice123")
        }
        val principal = service.toPrincipal(claims, "keycloak")

        principal.displayName shouldBe "alice123"
    }

    test("toPrincipal falls back to 'login' for displayName when 'name' and 'preferred_username' are absent") {
        val claims = buildJsonObject {
            put("sub", "u1")
            put("email", "u@example.com")
            put("login", "octocat")
        }
        val principal = service.toPrincipal(claims, "github")

        principal.displayName shouldBe "octocat"
    }

    test("toPrincipal sets displayName to null when no display name claim is present") {
        val claims = buildJsonObject {
            put("sub", "u1")
            put("email", "u@example.com")
        }
        val principal = service.toPrincipal(claims, "minimal")

        principal.displayName.shouldBeNull()
    }

    test("toPrincipal prefers 'sub' over 'id' and 'login' for userId") {
        val claims = buildJsonObject {
            put("sub", "primary-sub")
            put("id", "numeric-id")
            put("login", "username")
            put("email", "u@example.com")
        }
        val principal = service.toPrincipal(claims, "test")

        principal.userId shouldBe "primary-sub"
    }

    test("toPrincipal prefers 'name' over 'preferred_username' and 'login' for displayName") {
        val claims = buildJsonObject {
            put("sub", "u1")
            put("email", "u@example.com")
            put("name", "Full Name")
            put("preferred_username", "user123")
            put("login", "loginname")
        }
        val principal = service.toPrincipal(claims, "test")

        principal.displayName shouldBe "Full Name"
    }
})

