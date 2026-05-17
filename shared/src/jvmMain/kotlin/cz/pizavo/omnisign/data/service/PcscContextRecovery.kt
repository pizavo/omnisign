package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Recovers, from inside the running JVM, from the JDK `sun.security.smartcardio`
 * stale-PC/SC-context defect (JDK-8026326 / JDK-8074418 family).
 *
 * `sun.security.smartcardio.PCSCTerminals` establishes the process-wide PC/SC context
 * **once**, lazily, and caches the native handle in a `private static long contextId`.
 * If the platform smart-card service is unavailable at that moment — `pcscd` not yet up
 * on Linux, the Windows *Smart Card* service stopped or auto-stopped because no reader
 * was attached when the JVM first touched `javax.smartcardio` — the establish fails with
 * `SCARD_E_NO_SERVICE`, or yields a handle the service later invalidates.  The static
 * field is then **never re-established for the lifetime of the JVM**: every subsequent
 * `CardTerminals.list()` / `waitForChange()` throws `SCARD_E_SERVICE_STOPPED` (or
 * `SCARD_E_INVALID_HANDLE`), even after the service recovers, the reader is plugged in,
 * or the user triggers a manual rescan.  The only remedy the JDK itself offers is a full
 * JVM restart.
 *
 * This class breaks that dead-handle latch: when the stale-context signature is detected
 * ([isStaleContext]), [resetContext] reflectively zeroes `PCSCTerminals.contextId` and
 * re-invokes its `initContext()`, so the very next enumeration runs a fresh
 * `SCardEstablishContext`.  Zeroing the field is the load-bearing step — a zero context
 * forces the JDK to re-establish on next use; the explicit `initContext()` re-invoke is
 * only an optimisation so the *current* retry succeeds rather than needing one more
 * round-trip.
 *
 * All reflection is best-effort and fully guarded.  When the JDK internals are
 * unreachable — most commonly because the JVM was not started with
 * `--add-opens java.smartcardio/sun.security.smartcardio=ALL-UNNAMED` — [resetContext]
 * logs the actionable hint and returns `false` rather than throwing, so callers fall
 * back to their existing graceful-degradation path (an empty reader list) exactly as
 * they did before this class existed.
 */
class PcscContextRecovery {

	/**
	 * Whether [error], or any throwable in its `cause` chain, carries the JDK stale
	 * PC/SC context signature: a `SCARD_E_NO_SERVICE`, `SCARD_E_SERVICE_STOPPED` or
	 * `SCARD_E_INVALID_HANDLE` PC/SC return code.
	 *
	 * Note `SCARD_E_NO_READERS_AVAILABLE` is deliberately **not** a stale signature: the
	 * context is healthy, there are simply no readers attached, and a [resetContext]
	 * would be both wrong and wasteful — callers should keep polling instead (see
	 * [NO_READERS_AVAILABLE]).
	 *
	 * @param error The throwable caught from a `javax.smartcardio` call.
	 * @return `true` when a [resetContext] attempt is warranted.
	 */
	fun isStaleContext(error: Throwable): Boolean =
		STALE_CONTEXT_CODES.any { causeChainContains(error, it) }

	/**
	 * Whether [error], or any throwable within [MAX_CAUSE_DEPTH] levels of its `cause`
	 * chain, has a message containing [code].
	 *
	 * The PC/SC return code lives on the underlying
	 * `sun.security.smartcardio.PCSCException`, which
	 * `javax.smartcardio.CardException("list() failed", cause)` wraps, so the whole
	 * chain is inspected rather than only the top-level message.  The walk is
	 * depth-bounded to stay safe against a pathological self-referential cause chain.
	 *
	 * @param error The throwable to inspect.
	 * @param code The PC/SC return-code token to search for (e.g. `SCARD_E_NO_SERVICE`).
	 * @return `true` if [code] appears in any message along the chain.
	 */
	fun causeChainContains(error: Throwable, code: String): Boolean {
		var current: Throwable? = error
		var depth = 0
		while (current != null && depth < MAX_CAUSE_DEPTH) {
			if (current.message?.contains(code) == true) return true
			current = current.cause
			depth++
		}
		return false
	}

	/**
	 * Reflectively clear the cached static PC/SC context so the next `javax.smartcardio`
	 * enumeration re-establishes it.
	 *
	 * Zeroes `sun.security.smartcardio.PCSCTerminals.contextId` (the dead handle), then
	 * re-invokes its `static synchronized initContext()`, which calls
	 * `SCardEstablishContext` afresh because the field is now `0`.  Clearing the field is
	 * the part that actually matters and is reported as success even when the immediate
	 * re-establish still fails (service genuinely down): the latch is broken, so a later
	 * call retries cleanly instead of being stuck on the original dead handle forever.
	 *
	 * @return `true` if the stale handle was cleared (recovery is now possible); `false`
	 *   if the JDK internals could not be reached — in which case the JVM almost
	 *   certainly lacks `--add-opens java.smartcardio/sun.security.smartcardio=ALL-UNNAMED`.
	 */
	fun resetContext(): Boolean {
		val pcscTerminals = try {
			Class.forName("sun.security.smartcardio.PCSCTerminals")
		} catch (e: Throwable) {
			logger.warn(e) {
				"Could not load sun.security.smartcardio.PCSCTerminals to reset the stale PC/SC " +
					"context; the JDK smart-card internals are unavailable on this runtime"
			}
			return false
		}

		val cleared = try {
			val contextId = pcscTerminals.getDeclaredField("contextId")
			contextId.isAccessible = true
			val stale = contextId.getLong(null)
			contextId.setLong(null, 0L)
			logger.info {
				"Cleared stale PC/SC context handle (was $stale) — the next reader enumeration " +
					"will re-establish it"
			}
			true
		} catch (e: Throwable) {
			logger.warn(e) {
				"Could not reflectively reset the stale PC/SC context; start the JVM with " +
					"--add-opens java.smartcardio/sun.security.smartcardio=ALL-UNNAMED so smart-card " +
					"recovery works without a restart"
			}
			false
		}
		if (!cleared) return false

		try {
			val initContext = pcscTerminals.getDeclaredMethod("initContext")
			initContext.isAccessible = true
			initContext.invoke(null)
		} catch (e: Throwable) {
			logger.debug(e) {
				"PC/SC context cleared but the immediate re-establish failed (smart-card service " +
					"still unavailable); a later enumeration will retry once the service is back"
			}
		}
		return true
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * PC/SC return code raised when the context is valid but no readers are
		 * attached.  **Not** a stale-context signature: the watcher should keep
		 * polling (it cannot `waitForChange` on an empty reader set) rather than
		 * reset or terminate.  Exposed so reader-state consumers classify it
		 * consistently via [causeChainContains].
		 */
		const val NO_READERS_AVAILABLE = "SCARD_E_NO_READERS_AVAILABLE"

		/**
		 * Upper bound on the `cause`-chain walk in [causeChainContains].  Real
		 * `javax.smartcardio` failures nest at most two levels
		 * (`CardException` → `PCSCException`); the bound only guards against a
		 * pathological cyclic chain.
		 */
		const val MAX_CAUSE_DEPTH = 16

		/**
		 * PC/SC return codes that indicate the cached process-wide context is dead and
		 * will never recover without a [resetContext]: the service was down when the
		 * context was established (`SCARD_E_NO_SERVICE`), the service stopped after a
		 * valid context existed (`SCARD_E_SERVICE_STOPPED`), or the handle is otherwise
		 * no longer valid (`SCARD_E_INVALID_HANDLE`).
		 */
		val STALE_CONTEXT_CODES = listOf(
			"SCARD_E_NO_SERVICE",
			"SCARD_E_SERVICE_STOPPED",
			"SCARD_E_INVALID_HANDLE",
		)
	}
}
