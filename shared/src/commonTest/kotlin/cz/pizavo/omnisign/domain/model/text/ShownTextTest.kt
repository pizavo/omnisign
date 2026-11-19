package cz.pizavo.omnisign.domain.model.text

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies how [shownInMessage] writes a text that a document or another file gives, for an error
 * message: cut to its first 64 characters, with control, format and separator characters and
 * surrogates written as escapes and backslashes doubled.
 */
class ShownTextTest : FunSpec({

	test("leaves a text of printable characters as it is") {
		shownInMessage("expirationDateOverrides.RSA:1900") shouldBe "expirationDateOverrides.RSA:1900"
	}

	test("cuts a text after its first 64 characters and ends it with three dots") {
		shownInMessage("a".repeat(64)) shouldBe "a".repeat(64)
		shownInMessage("a".repeat(65)) shouldBe "a".repeat(64) + "..."
	}

	test("writes control, format and separator characters as escapes") {
		shownInMessage("a\u001B[31mb") shouldBe "a\\u001b[31mb"
		shownInMessage("a\nb") shouldBe "a\\u000ab"
		shownInMessage("x\u202Ey") shouldBe "x\\u202ey"
		shownInMessage("p\u2028q\u2029r") shouldBe "p\\u2028q\\u2029r"
	}

	test("doubles a backslash, so the text of an escape does not read as the escape") {
		shownInMessage("a\\u001bb") shouldBe "a\\\\u001bb"
	}

	test("writes the half of a character that the cut leaves as an escape") {
		shownInMessage("a".repeat(63) + "\uD83D\uDE00") shouldBe "a".repeat(63) + "\\ud83d..."
	}
})
