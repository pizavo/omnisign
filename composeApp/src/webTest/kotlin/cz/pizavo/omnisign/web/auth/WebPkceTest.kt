package cz.pizavo.omnisign.web.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * Verifies [generatePkceHandoff] produces a hand-off pair the server's `HandoffCodeStore` will
 * accept, using the browser's real Web Crypto API.
 *
 * The security property under test is that only the digest travels outward: an observer of the
 * redirect URL, or of the hand-off code that comes back in it, must not be able to redeem the code.
 * That holds only if the challenge really is the SHA-256 of the verifier and the verifier really is
 * unguessable, so both are checked rather than assumed — the digest against an independently written
 * computation, the randomness across repeated generation.
 */
class WebPkceTest : FunSpec({

	val base64Url = Regex("^[A-Za-z0-9_-]+$")

	test("generates a verifier of the RFC 7636 minimum length in the base64url alphabet") {
		val handoff = generatePkceHandoff()

		handoff.verifier.length shouldBe 43
		base64Url.matches(handoff.verifier) shouldBe true
	}

	test("generates a challenge in the base64url alphabet, unpadded") {
		val handoff = generatePkceHandoff()

		handoff.challenge.length shouldBe 43
		base64Url.matches(handoff.challenge) shouldBe true
		handoff.challenge.contains('=') shouldBe false
	}

	test("derives the challenge as the base64url SHA-256 of the verifier") {
		val handoff = generatePkceHandoff()

		val digest: JsString = independentSha256Base64Url(handoff.verifier).await()

		handoff.challenge shouldBe digest.toString()
	}

	test("sends only the digest outward, never the verifier itself") {
		val handoff = generatePkceHandoff()

		handoff.challenge shouldNotBe handoff.verifier
	}

	test("never repeats a verifier across logins") {
		val verifiers = mutableSetOf<String>()

		repeat(TRIALS) { verifiers += generatePkceHandoff().verifier }

		verifiers.size shouldBe TRIALS
	}
})

/** How many hand-offs the randomness check generates. */
private const val TRIALS = 16

/**
 * `BASE64URL(SHA-256(ASCII(value)))`, written independently of the implementation under test.
 *
 * Deliberately not a call into the production helper: what this pins down is the encoding around the
 * digest — the base64url substitutions and the stripped padding — which is where a hand-off pair
 * would silently stop matching what the server recomputes.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun independentSha256Base64Url(value: String): Promise<JsString> =
	js(
		"crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))" +
			".then(function(d){var bytes=Array.from(new Uint8Array(d));" +
			"var chars=bytes.map(function(b){return String.fromCharCode(b);}).join('');" +
			"return btoa(chars).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=/g,'');})",
	)
