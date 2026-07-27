package cz.pizavo.omnisign.web.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.localStorage
import kotlinx.browser.window

/**
 * Verifies the browser-side halves of the login round trip against real `sessionStorage` and a real
 * address bar: the PKCE verifier survives the redirect to the identity provider and is reclaimed
 * exactly once, and a spent hand-off code is taken back out of the URL.
 *
 * Both properties are security-relevant. A verifier that outlived its login, or lingered in
 * `localStorage` where another tab could read it, would undermine the binding between the redirect
 * and the exchange; a code left in the address bar would be carried into a refresh or a shared link.
 *
 * The cases that need a `code` in the URL rewrite it with `history.replaceState` and the original
 * href is restored after every test, so the page the runner is serving is left as it was found.
 */
class BrowserAuthNavigationTest : FunSpec({

	val storageKey = "omnisign_pkce_verifier"
	var originalHref = ""

	beforeTest {
		originalHref = window.location.href
		window.sessionStorage.removeItem(storageKey)
	}

	afterTest {
		window.history.replaceState(null, "", originalHref)
		window.sessionStorage.removeItem(storageKey)
	}

	test("keeps the verifier across the identity-provider round trip") {
		storeVerifier("verifier-1")

		window.sessionStorage.getItem(storageKey) shouldBe "verifier-1"
	}

	test("reclaims the verifier exactly once") {
		storeVerifier("verifier-1")

		takeStoredVerifier() shouldBe "verifier-1"
		takeStoredVerifier() shouldBe null
	}

	test("reports no verifier when the login did not start in this tab") {
		takeStoredVerifier() shouldBe null
	}

	test("treats a blank stored verifier as none") {
		window.sessionStorage.setItem(storageKey, "   ")

		takeStoredVerifier() shouldBe null
	}

	test("keeps the verifier out of localStorage so it cannot outlive the tab") {
		storeVerifier("verifier-1")

		localStorage.getItem(storageKey) shouldBe null
	}

	test("reads the hand-off code the server appended to the return URL") {
		window.history.replaceState(null, "", window.location.pathname + "?code=handoff-123")

		handoffCodeFromUrl() shouldBe "handoff-123"
	}

	test("reports no hand-off code on an ordinary page load") {
		window.history.replaceState(null, "", window.location.pathname)

		handoffCodeFromUrl() shouldBe null
	}

	test("reports no hand-off code when the parameter is present but empty") {
		window.history.replaceState(null, "", window.location.pathname + "?code=")

		handoffCodeFromUrl() shouldBe null
	}

	test("takes a spent hand-off code back out of the address bar") {
		window.history.replaceState(null, "", window.location.pathname + "?code=spent")

		clearHandoffCodeFromUrl()

		window.location.search shouldBe ""
		handoffCodeFromUrl() shouldBe null
	}

	test("returns to an origin and path carrying no query or hash") {
		window.history.replaceState(null, "", window.location.pathname + "?code=handoff-123")

		val returnTo = returnToUrl()

		returnTo.contains('?') shouldBe false
		returnTo.contains('#') shouldBe false
		returnTo.startsWith(window.location.origin) shouldBe true
	}

	test("percent-encodes a return URL for use as a query-parameter value") {
		encodeUriComponent("https://omnisign.test/app?next=a&b=c") shouldBe
			"https%3A%2F%2Fomnisign.test%2Fapp%3Fnext%3Da%26b%3Dc"
	}
})
