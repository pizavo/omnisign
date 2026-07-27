package cz.pizavo.omnisign.web.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [WebSessionState] exposes the signed-in flag as observable state, so the boot gate swaps
 * between the app and the login screen in place rather than by reloading the page.
 */
class WebSessionStateTest : FunSpec({

	test("starts unauthenticated so the gate shows the login screen") {
		WebSessionState().authenticated.value shouldBe false
	}

	test("publishes each change to its observers") {
		val state = WebSessionState()
		val observed = mutableListOf<Boolean>()

		observed += state.authenticated.value
		state.set(true)
		observed += state.authenticated.value
		state.set(false)
		observed += state.authenticated.value

		observed shouldBe listOf(false, true, false)
	}
})
