package cz.pizavo.omnisign.cli

import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * Verifies [resolvePasswordOption] sentinel and passthrough behavior.
 */
class PasswordOptionResolverTest : FunSpec({

	val callback = mockk<PasswordCallback>()

	test("null raw value returns null without prompting") {
		resolvePasswordOption(null, callback).shouldBeNull()
		verify(exactly = 0) { callback.requestPassword(any(), any()) }
	}

	test("literal value is returned as-is without prompting") {
		resolvePasswordOption("s3cret", callback) shouldBe "s3cret"
		verify(exactly = 0) { callback.requestPassword(any(), any()) }
	}

	test("sentinel '-' triggers interactive prompt and returns result") {
		every { callback.requestPassword(any(), any()) } returns "prompted-pw"

		resolvePasswordOption("-", callback) shouldBe "prompted-pw"
		verify(exactly = 1) { callback.requestPassword("Timestamp server password", "Timestamp Password") }
	}

	test("sentinel '-' returns null when user cancels prompt") {
		every { callback.requestPassword(any(), any()) } returns null

		resolvePasswordOption("-", callback).shouldBeNull()
	}

	test("custom prompt text is forwarded to callback") {
		every { callback.requestPassword(any(), any()) } returns "pw"

		resolvePasswordOption("-", callback, prompt = "Custom prompt") shouldBe "pw"
		verify { callback.requestPassword("Custom prompt", "Timestamp Password") }
	}

	test("PASSWORD_PROMPT_SENTINEL constant equals '-'") {
		PASSWORD_PROMPT_SENTINEL shouldBe "-"
	}
})

