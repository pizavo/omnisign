package cz.pizavo.omnisign.web.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [WebAuthState] holds and releases the session token pair as a whole — the invariant the
 * refresh interceptor depends on when it distinguishes "a sibling refreshed" from "the session died".
 */
class WebAuthStateTest : FunSpec({

	test("starts logged out") {
		val state = WebAuthState()

		state.accessToken shouldBe null
		state.refreshToken shouldBe null
	}

	test("records an issued token pair") {
		val state = WebAuthState()

		state.set(accessToken = "access-1", refreshToken = "refresh-1")

		state.accessToken shouldBe "access-1"
		state.refreshToken shouldBe "refresh-1"
	}

	test("replaces the pair wholesale on a rotation") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1")

		state.set(accessToken = "access-2", refreshToken = "refresh-2")

		state.accessToken shouldBe "access-2"
		state.refreshToken shouldBe "refresh-2"
	}

	test("clears both tokens together") {
		val state = WebAuthState()
		state.set(accessToken = "access-1", refreshToken = "refresh-1")

		state.clear()

		state.accessToken shouldBe null
		state.refreshToken shouldBe null
	}
})
