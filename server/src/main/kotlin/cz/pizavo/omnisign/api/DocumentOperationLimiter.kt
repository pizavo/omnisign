package cz.pizavo.omnisign.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Runs the API's document operations, which read an uploaded file and have DSS validate, sign,
 * extend or inspect it, on [Dispatchers.IO], at most [maxConcurrent] at a time.
 *
 * Ktor's Netty engine runs calls on a few call threads, by default one per processor, and pins each
 * connection to one of them. A document operation blocks the thread it runs on while DSS parses the
 * PDF and fetches revocation data, issuer certificates and timestamps over the network. On a call
 * thread, it would stall every other call pinned to that thread until it finished, health checks
 * and session refreshes included.
 *
 * [Dispatchers.IO] has threads to spare for blocking work, but it would lift the bound that the call
 * threads used to set on how many operations run at once, and each operation holds its document, of
 * up to `maxFileSize`, and what DSS builds from it in memory. The operations are therefore held to
 * [maxConcurrent]; any further ones wait, suspended, for a turn, with their uploads still on disk.
 *
 * @param maxConcurrent The most document operations that run at once; at least 1.
 */
class DocumentOperationLimiter(maxConcurrent: Int) {

	private val permits = Semaphore(maxConcurrent)

	/**
	 * Run [operation] on [Dispatchers.IO] once fewer than `maxConcurrent` operations are running,
	 * and return its result.
	 *
	 * Read the upload inside [operation], so that a request waiting for its turn holds no document in
	 * memory, and respond outside it, so that a slow client cannot keep other requests waiting.
	 *
	 * @param operation The blocking work: reading the upload and running the use case on it.
	 * @return What [operation] returns.
	 */
	suspend fun <T> execute(operation: suspend () -> T): T =
		permits.withPermit { withContext(Dispatchers.IO) { operation() } }
}
