package cz.pizavo.omnisign.data.repository

import javax.security.auth.login.FailedLoginException

/**
 * Whether this exception, or one of its causes, says that a PKCS#11 token refused the PIN it was
 * given.
 *
 * SunPKCS11 reports `CKR_PIN_INCORRECT` from `C_Login` as a [FailedLoginException], which DSS wraps
 * with the cause chain intact. The PKCS#11 error underneath is a JDK-internal exception whose message
 * starts with the return value's name, so `CKR_PIN_INCORRECT`, `CKR_PIN_INVALID` and
 * `CKR_PIN_LEN_RANGE` are recognised by message as well. A PIN refused with any of them is refused
 * again on every later attempt, and each `CKR_PIN_INCORRECT` costs one of the token's PIN retries.
 *
 * `CKR_PIN_LOCKED` is deliberately not a refusal of the PIN: a blocked token refuses every PIN until
 * it is unblocked, after which the same PIN may be right again.
 *
 * @return `true` when the token refused the PIN itself, `false` for any other failure.
 */
internal fun Throwable.isPinRejection(): Boolean =
	generateSequence(this) { it.cause }.any { cause ->
		cause is FailedLoginException || cause.message?.let { message ->
			message.startsWith("CKR_PIN_INCORRECT") ||
				message.startsWith("CKR_PIN_INVALID") ||
				message.startsWith("CKR_PIN_LEN_RANGE")
		} == true
	}
