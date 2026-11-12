package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.model.DSSException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.IOException
import javax.security.auth.login.FailedLoginException
import javax.security.auth.login.LoginException

/**
 * Verifies that [isPinRejection] recognises a PIN refused by a PKCS#11 token, in the exception chain
 * DSS and SunPKCS11 build for it, and nothing else.
 */
class IsPinRejectionTest : FunSpec({

	/**
	 * Build the chain DSS throws when SunPKCS11's `C_Login` fails with [code], reported by the
	 * provider as [login].
	 */
	fun loginFailure(login: LoginException, code: String): DSSException =
		DSSException("Can't initialize Sun PKCS#11 security provider", IOException("load failed", login.apply {
			initCause(Exception(code))
		}))

	test("a wrong PIN, as DSS reports it, is a refusal") {
		loginFailure(FailedLoginException(), "CKR_PIN_INCORRECT").isPinRejection().shouldBeTrue()
	}

	test("a wrong PIN is a refusal without a FailedLoginException in the chain") {
		IllegalStateException("load failed", Exception("CKR_PIN_INCORRECT")).isPinRejection().shouldBeTrue()
	}

	test("a PIN of a length or form the token refuses is a refusal") {
		loginFailure(LoginException(), "CKR_PIN_LEN_RANGE").isPinRejection().shouldBeTrue()
		loginFailure(LoginException(), "CKR_PIN_INVALID").isPinRejection().shouldBeTrue()
	}

	test("a blocked PIN is not a refusal of the PIN") {
		loginFailure(LoginException(), "CKR_PIN_LOCKED").isPinRejection().shouldBeFalse()
	}

	test("other token failures are not refusals") {
		loginFailure(LoginException(), "CKR_DEVICE_ERROR").isPinRejection().shouldBeFalse()
		IllegalStateException("PIN required for PKCS#11 token 'Card'").isPinRejection().shouldBeFalse()
	}
})
