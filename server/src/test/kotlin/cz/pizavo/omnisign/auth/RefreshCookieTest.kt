package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies the attributes of the browser-facing refresh cookie.
 *
 * Every one of these is a deliberate decision documented on [setRefreshCookie], and each fails
 * quietly if it regresses: a missing `Secure` puts a refresh token on the wire in clear text, a
 * drifting `Path` stops the browser sending the cookie to the one endpoint that consumes it, and a
 * stray `Max-Age` turns a session cookie into one that survives the browser closing. None of it
 * shows up as a failing request, which is why it is pinned here rather than left to the route specs
 * — those assert `HttpOnly` and `SameSite` and nothing else.
 */
class RefreshCookieTest : FunSpec({

	/**
	 * Issue a request against a route that sets the cookie, and return the rendered `Set-Cookie`.
	 *
	 * @param secure The flag under test, passed through to [setRefreshCookie].
	 * @param token The token to hand to the browser.
	 */
	suspend fun setCookieHeader(secure: Boolean, token: String = "r3fr3sh-token_AZ09"): String {
		var header = ""
		testApplication {
			application {
				routing {
					get("/set") {
						call.response.setRefreshCookie(token, secure = secure)
						call.respondText("ok")
					}
				}
			}
			header = client.get("/set").headers[HttpHeaders.SetCookie].orEmpty()
		}
		return header
	}

	/** The rendered `Set-Cookie` from a route that clears the cookie. */
	suspend fun clearCookieHeader(secure: Boolean): String {
		var header = ""
		testApplication {
			application {
				routing {
					get("/clear") {
						call.response.clearRefreshCookie(secure = secure)
						call.respondText("ok")
					}
				}
			}
			header = client.get("/clear").headers[HttpHeaders.SetCookie].orEmpty()
		}
		return header
	}

	test("marks the cookie Secure when the deployment terminates TLS") {
		setCookieHeader(secure = true) shouldContain "Secure"
	}

	test("omits Secure so the cookie is not dropped over plain-http local development") {
		setCookieHeader(secure = false) shouldNotContain "Secure"
	}

	test("keeps the cookie out of reach of script") {
		setCookieHeader(secure = true) shouldContain "HttpOnly"
	}

	test("withholds the cookie from genuinely cross-site requests") {
		setCookieHeader(secure = true) shouldContain "SameSite=Lax"
	}

	test("scopes the cookie to the one endpoint that consumes it") {
		setCookieHeader(secure = true) shouldContain "Path=/auth/refresh"
	}

	test("issues a session cookie that dies with the browser") {
		val header = setCookieHeader(secure = true)

		header shouldNotContain "Max-Age"
		header shouldNotContain "Expires"
	}

	test("keeps the cookie host-only so a sibling subdomain cannot read it") {
		setCookieHeader(secure = true) shouldNotContain "Domain"
	}

	test("renders no encoding marker onto a browser-facing cookie") {
		setCookieHeader(secure = true) shouldNotContain "x-enc"
	}

	test("carries a base64url token through unchanged") {
		val token = "abcXYZ089-_token"

		setCookieHeader(secure = true, token = token) shouldContain "$REFRESH_TOKEN_COOKIE=$token"
	}

	test("clears with the same path and Secure so the browser replaces the original") {
		val cleared = clearCookieHeader(secure = true)

		cleared shouldContain "Path=/auth/refresh"
		cleared shouldContain "Secure"
		cleared shouldContain "HttpOnly"
		cleared shouldContain "SameSite=Lax"
	}

	test("expires the cookie when clearing it") {
		clearCookieHeader(secure = true) shouldContain "Max-Age=0"
	}

	test("clearing an insecure cookie stays insecure so it still matches") {
		clearCookieHeader(secure = false) shouldNotContain "Secure"
	}

	test("reads the token back from the request") {
		testApplication {
			application {
				routing {
					get("/read") { call.respondText(call.refreshCookie() ?: "(none)") }
				}
			}

			val body = client.get("/read") {
				header(HttpHeaders.Cookie, "$REFRESH_TOKEN_COOKIE=abcXYZ089-_token")
			}.bodyAsText()

			body shouldBe "abcXYZ089-_token"
		}
	}

	test("reports no token when the cookie is absent") {
		testApplication {
			application {
				routing {
					get("/read") { call.respondText(call.refreshCookie() ?: "(none)") }
				}
			}

			client.get("/read").bodyAsText() shouldBe "(none)"
		}
	}

	test("treats a cleared-but-not-yet-dropped cookie as no token") {
		testApplication {
			application {
				routing {
					get("/read") { call.respondText(call.refreshCookie() ?: "(none)") }
				}
			}

			val body = client.get("/read") {
				header(HttpHeaders.Cookie, "$REFRESH_TOKEN_COOKIE=")
			}.bodyAsText()

			body shouldBe "(none)"
		}
	}
})
