package cz.pizavo.omnisign.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unit tests for [OidcUserInfoService]: the [toPrincipal][OidcUserInfoService.toPrincipal]
 * fallback chain for userId, email, and displayName, the reading of the `email_verified`
 * flag, and the GitHub email-list lookup.
 *
 * The shared [HttpClient] has a [MockEngine] that fails any request, since the mapping and
 * flag tests make none; the GitHub lookup tests build a client whose engine stands in for
 * GitHub's email list.
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

    test("toPrincipal returns null email when 'email' is absent (GitHub-private-email case)") {
        val claims = buildJsonObject {
            put("sub", "12345678")
            put("login", "octocat")
        }
        val principal = service.toPrincipal(claims, "github")

        principal.email shouldBe null
    }

    test("toPrincipal does NOT synthesise an email from 'login' when 'email' is absent") {
        val claims = buildJsonObject {
            put("sub", "user-abc")
            put("login", "synthetic-not-email")
        }
        val principal = service.toPrincipal(claims, "noemail")

        principal.email shouldBe null
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

    test("emailVerified reads a boolean email_verified claim") {
        service.emailVerified(buildJsonObject { put("email_verified", true) }) shouldBe true
        service.emailVerified(buildJsonObject { put("email_verified", false) }) shouldBe false
    }

    test("emailVerified reads the string form in any case, as Cognito's UserInfo sends it") {
        service.emailVerified(buildJsonObject { put("email_verified", "true") }) shouldBe true
        service.emailVerified(buildJsonObject { put("email_verified", "False") }) shouldBe false
    }

    test("emailVerified reports no flag when the claim is absent or JSON null") {
        service.emailVerified(buildJsonObject { put("email", "u@example.com") }).shouldBeNull()
        service.emailVerified(buildJsonObject { put("email_verified", JsonNull) }).shouldBeNull()
    }

    test("emailVerified reads a claim holding anything but a recognisable yes or no as no") {
        service.emailVerified(buildJsonObject { put("email_verified", 0) }) shouldBe false
        service.emailVerified(buildJsonObject { put("email_verified", 1) }) shouldBe false
        service.emailVerified(buildJsonObject { put("email_verified", "no") }) shouldBe false
        service.emailVerified(buildJsonObject { put("email_verified", buildJsonObject { }) }) shouldBe false
    }

    /**
     * A service whose GitHub answers every request with [body] and [status], recording each
     * request in [requests].
     */
    fun githubService(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        requests: MutableList<HttpRequestData> = mutableListOf(),
    ) = OidcUserInfoService(
        HttpClient(MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
    )

    val octocatEmails = """
        [
          {"email": "Octocat@Example.com", "verified": true, "primary": true, "visibility": "public"},
          {"email": "octo@unverified.example", "verified": false, "primary": false, "visibility": null}
        ]
    """.trimIndent()

    test("fetchGitHubEmailVerified asks GitHub's email list with the login's token") {
        val requests = mutableListOf<HttpRequestData>()

        githubService(octocatEmails, requests = requests).fetchGitHubEmailVerified("gh-token", "octocat@example.com")

        requests.single().url.toString() shouldBe OidcUserInfoService.GITHUB_USER_EMAILS_API_URL
        requests.single().headers[HttpHeaders.Authorization] shouldBe "Bearer gh-token"
    }

    test("fetchGitHubEmailVerified reads the verified flag of the listed address, ignoring case") {
        githubService(octocatEmails).fetchGitHubEmailVerified("gh-token", "octocat@example.com") shouldBe true
    }

    test("fetchGitHubEmailVerified reports an address GitHub lists as unverified") {
        githubService(octocatEmails).fetchGitHubEmailVerified("gh-token", "octo@unverified.example") shouldBe false
    }

    test("fetchGitHubEmailVerified reports no flag for an address the list does not include") {
        githubService(octocatEmails).fetchGitHubEmailVerified("gh-token", "someone@example.com").shouldBeNull()
    }

    test("fetchGitHubEmailVerified reports no flag when GitHub refuses the list, as without the user:email scope") {
        githubService("""{"message": "Not Found"}""", HttpStatusCode.NotFound)
            .fetchGitHubEmailVerified("gh-token", "octocat@example.com")
            .shouldBeNull()
    }

    test("fetchGitHubEmailVerified reports no flag when the list is no list") {
        githubService("""{"message": "unexpected"}""")
            .fetchGitHubEmailVerified("gh-token", "octocat@example.com")
            .shouldBeNull()
    }

    test("fetchGitHubEmailVerified does not let a verified lookalike vouch for the address") {
        val lookalike = """[{"email": "ceo@fabrıkam.com", "verified": true, "primary": false, "visibility": null}]"""

        githubService(lookalike).fetchGitHubEmailVerified("gh-token", "ceo@fabrikam.com").shouldBeNull()
    }

    test("fetchGitHubEmailVerified lets any listing of the address that says no win") {
        val twice = """
            [
              {"email": "Octocat@Example.com", "verified": true, "primary": true, "visibility": "public"},
              {"email": "octocat@example.com", "verified": false, "primary": false, "visibility": null}
            ]
        """.trimIndent()

        githubService(twice).fetchGitHubEmailVerified("gh-token", "octocat@example.com") shouldBe false
    }
})

