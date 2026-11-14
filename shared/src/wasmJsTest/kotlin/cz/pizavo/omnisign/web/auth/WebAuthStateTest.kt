package cz.pizavo.omnisign.web.auth

import cz.pizavo.omnisign.testing.sessionUser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [WebAuthState] holds and releases the session token pair, and the user it belongs to, as
 * a whole — the invariant the refresh interceptor depends on when it distinguishes "a sibling
 * refreshed" from "the session died", and [WebAuthApi.refresh] when it judges whose session the
 * cookie holds.
 */
class WebAuthStateTest : FunSpec({

	test("starts logged out") {
		val state = WebAuthState()

		state.accessToken shouldBe null
		state.refreshToken shouldBe null
		state.user shouldBe null
	}

	test("records an issued token pair and its user") {
		val state = WebAuthState()

		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())

		state.accessToken shouldBe "access-1"
		state.refreshToken shouldBe "refresh-1"
		state.user shouldBe sessionUser()
	}

	test("replaces the pair wholesale on a rotation") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())

		state.set(accessToken = "access-2", refreshToken = "refresh-2", user = sessionUser())

		state.accessToken shouldBe "access-2"
		state.refreshToken shouldBe "refresh-2"
	}

	test("clears both tokens and their user together") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1", user = sessionUser())

		state.clear()

		state.accessToken shouldBe null
		state.refreshToken shouldBe null
		state.user shouldBe null
	}
})
