package cz.pizavo.omnisign.web.auth

import cz.pizavo.omnisign.testing.bodyText
import cz.pizavo.omnisign.testing.mockApiClient
import cz.pizavo.omnisign.testing.sessionUser
import cz.pizavo.omnisign.testing.tokenResponseJson
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Verifies [WebAuthApi] reads the `/auth` endpoints as *values* rather than exceptions — the reason
 * it runs on a bare client — and in particular that it tells the three refresh outcomes apart, since
 * conflating an unreachable server with a dead session would sign users out on a network blip. It
 * also checks that a tab whose refresh token another tab has spent carries on with the cookie's, but
 * only as the same user.
 */
class WebAuthApiTest : FunSpec({

	val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

	test("lists the providers the server offers") {
		val state = WebAuthState()
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				respond(
					content = """{"providers":[{"name":"corp","displayName":"Corp SSO","type":"oidc","loginUrl":"/auth/login/corp"}]}""",
					headers = jsonHeaders,
				)
			},
			state,
		)

		val providers = api.loginOptions()

		providers.map { it.name } shouldBe listOf("corp")
		providers.single().loginUrl shouldBe "/auth/login/corp"
	}

	test("reports no providers when auth is not configured") {
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				respond(content = "", status = HttpStatusCode.ServiceUnavailable)
			},
			WebAuthState(),
		)

		api.loginOptions() shouldBe emptyList()
	}

	test("reports no providers when the server is unreachable") {
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { throw RuntimeException("connection refused") },
			WebAuthState(),
		)

		api.loginOptions() shouldBe emptyList()
	}

	test("reports no providers when the body cannot be read") {
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { respond(content = "<html/>", headers = jsonHeaders) },
			WebAuthState(),
		)

		api.loginOptions() shouldBe emptyList()
	}

	test("exchanges a hand-off code and its verifier for a session") {
		val state = WebAuthState()
		var method: HttpMethod? = null
		var url = ""
		var body = ""
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { request ->
				method = request.method
				url = request.url.toString()
				body = request.body.bodyText()
				respond(content = tokenResponseJson("access-1", "refresh-1"), headers = jsonHeaders)
			},
			state,
		)

		api.exchange(code = "handoff-code", verifier = "pkce-verifier") shouldBe true

		method shouldBe HttpMethod.Post
		url shouldContain "auth/exchange"
		body shouldContain "handoff-code"
		body shouldContain "pkce-verifier"
		state.accessToken shouldBe "access-1"
		state.refreshToken shouldBe "refresh-1"
		state.user shouldBe sessionUser()
	}

	test("rejects an expired or mismatched hand-off code without touching the session") {
		val state = WebAuthState()
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				respond(content = """{"error":"INVALID_CODE","message":"unknown"}""", status = HttpStatusCode.BadRequest, headers = jsonHeaders)
			},
			state,
		)

		api.exchange(code = "stale", verifier = "v") shouldBe false

		state.accessToken shouldBe null
	}

	test("refreshes mid-session by spending the held refresh token") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
		var body = ""
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { request ->
				body = request.body.bodyText()
				respond(content = tokenResponseJson("access-2", "refresh-2"), headers = jsonHeaders)
			},
			state,
		)

		api.refresh() shouldBe RefreshOutcome.Refreshed

		body shouldContain "refresh-1"
		state.accessToken shouldBe "access-2"
		state.refreshToken shouldBe "refresh-2"
	}

	test("refreshes after a reload with an empty body so the server reads its cookie") {
		val state = WebAuthState()
		var body = "unset"
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { request ->
				body = request.body.bodyText()
				respond(content = tokenResponseJson("access-1", "refresh-1"), headers = jsonHeaders)
			},
			state,
		)

		api.refresh() shouldBe RefreshOutcome.Refreshed

		body shouldBe ""
		state.accessToken shouldBe "access-1"
	}

	test("reports a rejected refresh token as the end of the session") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				respond(content = "", status = HttpStatusCode.Unauthorized)
			},
			state,
		)

		api.refresh() shouldBe RefreshOutcome.SessionOver
	}

	test("reports a server error as transient so a blip is not mistaken for a sign-out") {
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				respond(content = "", status = HttpStatusCode.BadGateway)
			},
			WebAuthState(),
		)

		api.refresh() shouldBe RefreshOutcome.TransientError
	}

	test("reports an unreachable server as transient") {
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { throw RuntimeException("connection refused") },
			WebAuthState(),
		)

		api.refresh() shouldBe RefreshOutcome.TransientError
	}

	test("falls back to the cookie when another tab has spent the held refresh token") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
		val bodies = mutableListOf<String>()
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { request ->
				bodies += request.body.bodyText()
				if (bodies.size == 1) {
					respond(
						content = """{"error":"INVALID_REFRESH_TOKEN","message":"already used"}""",
						status = HttpStatusCode.Unauthorized,
						headers = jsonHeaders,
					)
				} else {
					respond(content = tokenResponseJson("access-3", "refresh-3"), headers = jsonHeaders)
				}
			},
			state,
		)

		api.refresh() shouldBe RefreshOutcome.Refreshed

		bodies.size shouldBe 2
		bodies[0] shouldContain "refresh-1"
		bodies[1] shouldBe ""
		state.accessToken shouldBe "access-3"
		state.refreshToken shouldBe "refresh-3"
	}

	test("does not carry on as another user whose session the cookie holds") {
		for (other in listOf(sessionUser(userId = "user-2"), sessionUser(providerName = "other-idp"))) {
			val state = WebAuthState()
			state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
			var requests = 0
			val api = WebAuthApi(
				mockApiClient(expectSuccess = false) {
					requests++
					if (requests == 1) {
						respond(content = "", status = HttpStatusCode.Unauthorized)
					} else {
						respond(content = tokenResponseJson("their-access", "their-refresh", other), headers = jsonHeaders)
					}
				},
				state,
			)

			withClue(other.toString()) {
				api.refresh() shouldBe RefreshOutcome.SessionOver
				requests shouldBe 2
				state.accessToken shouldBe "access-1"
				state.user shouldBe sessionUser()
			}
		}
	}

	test("reports the end of the session when the cookie offers nothing either") {
		for (cookieAnswer in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.BadRequest)) {
			val state = WebAuthState()
			state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
			var requests = 0
			val api = WebAuthApi(
				mockApiClient(expectSuccess = false) {
					requests++
					respond(content = "", status = if (requests == 1) HttpStatusCode.Unauthorized else cookieAnswer)
				},
				state,
			)

			withClue(cookieAnswer.toString()) {
				api.refresh() shouldBe RefreshOutcome.SessionOver
				requests shouldBe 2
			}
		}
	}

	test("keeps the session when the cookie fallback cannot reach the server") {
		for (unreachable in listOf(true, false)) {
			val state = WebAuthState()
			state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
			var requests = 0
			val api = WebAuthApi(
				mockApiClient(expectSuccess = false) {
					requests++
					when {
						requests == 1 -> respond(content = "", status = HttpStatusCode.Unauthorized)
						unreachable -> throw RuntimeException("connection refused")
						else -> respond(content = "", status = HttpStatusCode.BadGateway)
					}
				},
				state,
			)

			withClue(if (unreachable) "unreachable" else "502") {
				api.refresh() shouldBe RefreshOutcome.TransientError
				requests shouldBe 2
			}
		}
	}

	test("tries the cookie only after the server has refused the held token") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
		var requests = 0
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				requests++
				respond(content = "", status = HttpStatusCode.BadGateway)
			},
			state,
		)

		api.refresh() shouldBe RefreshOutcome.TransientError

		requests shouldBe 1
	}

	test("reports a refresh without a body that finds no cookie as no session, and asks only once") {
		for (answer in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)) {
			var requests = 0
			val api = WebAuthApi(
				mockApiClient(expectSuccess = false) {
					requests++
					respond(content = "", status = answer)
				},
				WebAuthState(),
			)

			withClue(answer.toString()) {
				api.refresh() shouldBe RefreshOutcome.SessionOver
				requests shouldBe 1
			}
		}
	}

	test("keeps the rotated pair when the caller is cancelled while the refresh is in flight") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
		val requestArrived = CompletableDeferred<Unit>()
		val answer = CompletableDeferred<Unit>()
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) {
				requestArrived.complete(Unit)
				answer.await()
				respond(content = tokenResponseJson("access-2", "refresh-2"), headers = jsonHeaders)
			},
			state,
		)

		coroutineScope {
			val caller = launch { api.refresh() }
			requestArrived.await()
			caller.cancel()
			answer.complete(Unit)
			caller.join()
		}

		state.accessToken shouldBe "access-2"
		state.refreshToken shouldBe "refresh-2"
	}

	test("revokes the session server-side and clears the tokens") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
		var url = ""
		var body = ""
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { request ->
				url = request.url.toString()
				body = request.body.bodyText()
				respond(content = "", status = HttpStatusCode.NoContent)
			},
			state,
		)

		api.logout()

		url shouldContain "auth/logout"
		body shouldContain "refresh-1"
		state.accessToken shouldBe null
		state.refreshToken shouldBe null
	}

	test("clears the tokens even when the revocation call fails") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())
		val api = WebAuthApi(
			mockApiClient(expectSuccess = false) { throw RuntimeException("connection refused") },
			state,
		)

		api.logout()

		state.accessToken shouldBe null
		state.refreshToken shouldBe null
	}
})
