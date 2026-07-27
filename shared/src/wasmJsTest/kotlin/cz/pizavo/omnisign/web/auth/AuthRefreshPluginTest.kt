package cz.pizavo.omnisign.web.auth

import cz.pizavo.omnisign.testing.mockApiClient
import cz.pizavo.omnisign.testing.tokenResponseJson
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Verifies [authRefreshPlugin] keeps a session alive across the access token's ~5-minute lifetime:
 * a `401` triggers one refresh and a retry that carries the *new* token, concurrent `401`s refresh
 * once between them, and only a genuinely rejected refresh token — never an unreachable server —
 * drops the user to the login gate.
 */
class AuthRefreshPluginTest : FunSpec({

	val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

	test("passes a successful response through without refreshing") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1")
		var refreshes = 0
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				refreshes++
				respond(content = tokenResponseJson("access-2", "refresh-2"), headers = jsonHeaders)
			},
			state,
		)
		var expirations = 0
		val client = apiClientWith(state, api, onSessionExpired = { expirations++ }) { respond(content = "ok") }

		client.get("https://omnisign.test/api/v1/capabilities").status shouldBe HttpStatusCode.OK

		refreshes shouldBe 0
		expirations shouldBe 0
	}

	test("refreshes on a 401 and retries carrying the new token") {
		val state = WebAuthState()
		state.set(accessToken = "stale", refreshToken = "refresh-1")
		var refreshes = 0
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				refreshes++
				respond(content = tokenResponseJson("fresh", "refresh-2"), headers = jsonHeaders)
			},
			state,
		)
		val sentAuthorizations = mutableListOf<String?>()
		val client = apiClientWith(state, api, onSessionExpired = { }) { request ->
			sentAuthorizations += request.headers[HttpHeaders.Authorization]
			if (sentAuthorizations.size == 1) {
				respond(content = "", status = HttpStatusCode.Unauthorized)
			} else {
				respond(content = "ok")
			}
		}

		client.get("https://omnisign.test/api/v1/capabilities").status shouldBe HttpStatusCode.OK

		refreshes shouldBe 1
		sentAuthorizations shouldBe listOf("Bearer stale", "Bearer fresh")
		state.accessToken shouldBe "fresh"
	}

	test("refreshes once for concurrent 401s rather than rotating the refresh token twice") {
		val state = WebAuthState()
		state.set(accessToken = "stale", refreshToken = "refresh-1")
		var refreshes = 0
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				refreshes++
				respond(content = tokenResponseJson("fresh", "refresh-2"), headers = jsonHeaders)
			},
			state,
		)
		var served = 0
		val client = apiClientWith(state, api, onSessionExpired = { }) {
			served++
			if (served <= 2) respond(content = "", status = HttpStatusCode.Unauthorized) else respond(content = "ok")
		}

		val responses = coroutineScope {
			listOf(
				async { client.get("https://omnisign.test/api/v1/capabilities") },
				async { client.get("https://omnisign.test/api/v1/config/global") },
			).awaitAll()
		}

		responses.map { it.status } shouldBe listOf(HttpStatusCode.OK, HttpStatusCode.OK)
		refreshes shouldBe 1
		state.refreshToken shouldBe "refresh-2"
	}

	test("drops to the login gate when the server rejects the refresh token") {
		val state = WebAuthState()
		state.set(accessToken = "stale", refreshToken = "revoked")
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { respond(content = "", status = HttpStatusCode.Unauthorized) },
			state,
		)
		var expirations = 0
		val client = apiClientWith(state, api, onSessionExpired = { expirations++ }) {
			respond(content = "", status = HttpStatusCode.Unauthorized)
		}

		shouldThrowAny { client.get("https://omnisign.test/api/v1/capabilities") }

		expirations shouldBe 1
	}

	test("keeps the session when the refresh call merely fails to reach the server") {
		val state = WebAuthState()
		state.set(accessToken = "stale", refreshToken = "refresh-1")
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { respond(content = "", status = HttpStatusCode.BadGateway) },
			state,
		)
		var expirations = 0
		val client = apiClientWith(state, api, onSessionExpired = { expirations++ }) {
			respond(content = "", status = HttpStatusCode.Unauthorized)
		}

		shouldThrowAny { client.get("https://omnisign.test/api/v1/capabilities") }

		expirations shouldBe 0
		state.accessToken shouldBe "stale"
		state.refreshToken shouldBe "refresh-1"
	}
})

/**
 * The session-carrying API client as [cz.pizavo.omnisign.di.webDataModule] assembles it: bearer token
 * stamped by `defaultRequest`, `expectSuccess` on, and the refresh interceptor installed — the exact
 * arrangement whose interaction the plugin has to survive, since the retry does not re-run
 * `defaultRequest`.
 *
 * @param handler Answers each request the spec issues, including the plugin's retry.
 */
private fun apiClientWith(
	authState: WebAuthState,
	authApi: WebAuthApi,
	onSessionExpired: () -> Unit,
	handler: MockRequestHandler,
): HttpClient = HttpClient(MockEngine) {
	expectSuccess = true
	install(authRefreshPlugin(authState, authApi, onSessionExpired))
	defaultRequest {
		authState.accessToken?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
	}
	engine { addHandler(handler) }
}
