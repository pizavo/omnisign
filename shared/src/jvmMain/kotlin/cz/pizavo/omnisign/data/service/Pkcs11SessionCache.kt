package cz.pizavo.omnisign.data.service

import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import eu.europa.esig.dss.token.DSSPrivateKeyEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide cache of **unlocked** PKCS#11 signing sessions, keyed by the stable token id
 * (`pkcs11-<tokenSerial>`).
 *
 * A PKCS#11 token that hides its certificates behind the user PIN must be authenticated
 * (`C_Login`) before its keys can be enumerated, and DSS re-runs that login on every
 * `keyStore.load()`.  On middleware that drives its own secure PIN pad, each login is a
 * separate pad prompt.  When the user deliberately **unlocks** such a token, this cache holds
 * the opened [AbstractSignatureTokenConnection] together with the keys enumerated in that one
 * login, so the immediately-following signature (and any later one on the same inserted card)
 * reuses them instead of re-authenticating.
 *
 * What is **not** cached: the signing PIN. A held session only carries the user-PIN
 * authentication (certificate read); the signature itself runs through `C_Sign`, which the card
 * re-authenticates per operation for `CKA_ALWAYS_AUTHENTICATE` keys. So a cached session can
 * never be abused to produce a signature without the per-operation signing PIN.
 *
 * Lifetime: an entry lives until the card is removed / the reader is unplugged (the
 * [Pkcs11CacheInvalidator] calls [invalidateAll] on PC/SC reader-state changes) or the process
 * exits ([close]). There is intentionally no idle relock — the cached material is the
 * certificate, which is public.
 *
 * Thread-safety: all map mutations are guarded by [mutex]. [close] runs a best-effort,
 * unsynchronised drain because it is only invoked at shutdown.
 */
class Pkcs11SessionCache : AutoCloseable {

	private val mutex = Mutex()
	private val sessions = mutableMapOf<String, CachedSession>()

	/**
	 * Return the unlocked session held for [tokenId], or null when the token is not unlocked.
	 */
	suspend fun get(tokenId: String): CachedSession? = mutex.withLock { sessions[tokenId] }

	/**
	 * Store [session] under [tokenId], closing and replacing any session previously held for
	 * that id (a fresh unlock supersedes a stale one).
	 */
	suspend fun put(tokenId: String, session: CachedSession) {
		mutex.withLock { sessions.put(tokenId, session) }?.let { closeQuietly(it.token) }
	}

	/**
	 * Drop and close the session held for [tokenId], if any. No-op when nothing is cached.
	 */
	suspend fun invalidate(tokenId: String) {
		mutex.withLock { sessions.remove(tokenId) }?.let { closeQuietly(it.token) }
	}

	/**
	 * Drop and close every cached session. Called on any PC/SC reader-state change so a removed
	 * or swapped card can never leave a stale, unusable session behind.
	 */
	suspend fun invalidateAll() {
		val drained = mutex.withLock {
			val copy = sessions.values.toList()
			sessions.clear()
			copy
		}
		drained.forEach { closeQuietly(it.token) }
	}

	/**
	 * Best-effort close of all held sessions at process shutdown. Unsynchronised by design — it
	 * is only invoked when the owning Koin scope is torn down and no operations are in flight.
	 */
	override fun close() {
		sessions.values.forEach { closeQuietly(it.token) }
		sessions.clear()
	}

	private fun closeQuietly(token: AbstractSignatureTokenConnection) {
		try {
			token.close()
		} catch (e: Exception) {
			logger.debug(e) { "Failed to close a cached PKCS#11 session; ignoring" }
		}
	}

	/**
	 * An unlocked token: the open connection and the private-key entries enumerated during its
	 * single login. The keys are reused directly (never re-read via `token.keys`, which would
	 * re-authenticate).
	 *
	 * @property token The opened, authenticated token connection.
	 * @property keys The private-key entries enumerated once when the token was unlocked.
	 */
	data class CachedSession(
		val token: AbstractSignatureTokenConnection,
		val keys: List<DSSPrivateKeyEntry>,
	)

	private companion object {
		val logger = KotlinLogging.logger {}
	}
}
