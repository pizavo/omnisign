package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import javax.smartcardio.CardException

/**
 * Verifies [PcscContextRecovery.isStaleContext] recognises the JDK stale-PC/SC-context
 * signature anywhere in the `cause` chain and rejects unrelated failures.
 *
 * Only the pure classifier is unit-tested here: [PcscContextRecovery.resetContext]
 * mutates a JDK-internal static via reflection and is exercised end-to-end (with a
 * mocked recovery) by `PcscMonitorServiceTest`, keeping these tests hermetic.
 */
class PcscContextRecoveryTest : FunSpec({

	val recovery = PcscContextRecovery()

	test("isStaleContext is true when the top-level message carries SCARD_E_NO_SERVICE") {
		recovery.isStaleContext(CardException("SCARD_E_NO_SERVICE")) shouldBe true
	}

	test("isStaleContext is true when a wrapped cause carries SCARD_E_SERVICE_STOPPED") {
		val error = CardException("list() failed", RuntimeException("SCARD_E_SERVICE_STOPPED"))
		recovery.isStaleContext(error) shouldBe true
	}

	test("isStaleContext is true for SCARD_E_INVALID_HANDLE nested several levels deep") {
		val root = IllegalStateException("SCARD_E_INVALID_HANDLE")
		val mid = RuntimeException("middleware failure", root)
		val top = CardException("list() failed", mid)
		recovery.isStaleContext(top) shouldBe true
	}

	test("isStaleContext is false for an unrelated smart-card failure") {
		val error = CardException("Mute card", RuntimeException("boom"))
		recovery.isStaleContext(error) shouldBe false
	}

	test("isStaleContext is false and does not throw for a throwable without a message or cause") {
		recovery.isStaleContext(RuntimeException()) shouldBe false
	}

	test("isStaleContext is false for SCARD_E_NO_READERS_AVAILABLE — a healthy empty context, not a stale one") {
		val error = CardException("list() failed", RuntimeException("SCARD_E_NO_READERS_AVAILABLE"))
		recovery.isStaleContext(error) shouldBe false
	}

	test("causeChainContains finds the code in a wrapped cause") {
		val error = CardException("waitForChange() failed", RuntimeException("SCARD_E_NO_READERS_AVAILABLE"))
		recovery.causeChainContains(error, PcscContextRecovery.NO_READERS_AVAILABLE) shouldBe true
	}

	test("causeChainContains is false when the code is absent from the whole chain") {
		val error = CardException("list() failed", RuntimeException("SCARD_E_SERVICE_STOPPED"))
		recovery.causeChainContains(error, PcscContextRecovery.NO_READERS_AVAILABLE) shouldBe false
	}

	test("NO_READERS_AVAILABLE constant matches the JDK PC/SC return-code token") {
		PcscContextRecovery.NO_READERS_AVAILABLE shouldBe "SCARD_E_NO_READERS_AVAILABLE"
	}
})
