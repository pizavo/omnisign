package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * Concurrency gate that allows at most one execution of a suspending block at a time,
 * with at most one additional execution queued behind it.
 *
 * When multiple callers invoke [runOrCoalesce] concurrently:
 * - The first caller becomes the **leader** and executes the block immediately.
 * - Subsequent callers **coalesce** into a single pending slot and suspend until a
 *   fresh result is available.
 * - When the leader's block completes and a pending request exists, the leader's
 *   result is **discarded** (it may be stale — e.g., hardware state changed since the
 *   pending request was filed).  The leader re-executes the block and returns the
 *   fresh result to itself and all coalesced waiters.
 * - This loop repeats until a block execution completes with no pending request,
 *   guaranteeing that every caller receives the result of the latest quiescent run.
 *
 * **Cancellation**: if the leader's coroutine is canceled, all coalesced waiters
 * receive a [kotlinx.coroutines.CancellationException] and the gate returns to the
 * idle state so the next caller can become a new leader.
 *
 * Thread-safety is provided by a single [synchronized] monitor ([lock]) that protects
 * the [running] flag and the [pendingDeferred] slot.  The actual block executes
 * **outside** the monitor, so long-running work does not hold any lock.
 *
 * @param T The result type produced by the gated block.
 */
class ConflatedProbeGate<T> {

	/**
	 * Monitor protecting [running] and [pendingDeferred].
	 */
	private val lock = Any()

	/**
	 * Whether a leader is currently executing the block.
	 */
	private var running = false

	/**
	 * The single coalesced slot for callers that arrive while the leader is executing.
	 *
	 * All coalesced callers share the same [CompletableDeferred].  When the leader
	 * finishes a run, it atomically drains this slot and — if non-null — re-executes
	 * the block so the coalesced callers receive a fresh result.
	 */
	private var pendingDeferred: CompletableDeferred<T>? = null

	/**
	 * Execute [block] with conflated concurrency guarantees.
	 *
	 * At most one [block] runs at a time.  If a block is already running, this
	 * call coalesces into the single pending slot and suspends until the freshest
	 * result is available.
	 *
	 * @param block The suspending operation to execute (e.g., PKCS#11 discovery).
	 * @return The result of the latest quiescent [block] execution.
	 */
	suspend fun runOrCoalesce(block: suspend () -> T): T {
		val deferred = synchronized(lock) {
			if (!running) {
				running = true
				logger.debug { "No probe running — becoming leader" }
				null
			} else {
				val existing = pendingDeferred
				if (existing != null) {
					logger.debug { "Probe running with pending request — coalescing into existing waiter" }
					existing
				} else {
					val d = CompletableDeferred<T>()
					pendingDeferred = d
					logger.debug { "Probe running — creating pending request and suspending" }
					d
				}
			}
		}

		return if (deferred == null) {
			executeAsLeader(block)
		} else {
			deferred.await()
		}
	}

	/**
	 * Run [block] in a loop until no new pending request arrives during execution.
	 *
	 * After each run, the pending slot is drained atomically.  If non-null, the result
	 * is discarded and the block is re-executed.  Accumulated waiters are completed only
	 * when the system reaches quiescence (no pending after a run).
	 *
	 * The [running] flag is set to `false` **inside** the same [synchronized] block that
	 * checks the pending slot, eliminating the race between a leader finishing and a new
	 * caller arriving.
	 */
	private suspend fun executeAsLeader(block: suspend () -> T): T {
		val accumulatedWaiters = mutableListOf<CompletableDeferred<T>>()
		var iteration = 0

		try {
			while (true) {
				iteration++
				logger.debug { "Leader executing probe (iteration $iteration)" }

				val result = block()

				val waiter = synchronized(lock) {
					val w = pendingDeferred
					pendingDeferred = null
					if (w == null) {
						running = false
					}
					w
				}

				if (waiter == null) {
					logger.debug {
						"Probe complete — no pending request, returning result " +
								"to leader + ${accumulatedWaiters.size} coalesced waiter(s)"
					}
					accumulatedWaiters.forEach { it.complete(result) }
					return result
				}

				logger.debug {
					"Probe complete — pending request found, discarding result and re-probing " +
							"(accumulated ${accumulatedWaiters.size + 1} waiter(s) so far)"
				}
				accumulatedWaiters.add(waiter)
			}
		} catch (e: Throwable) {
			val waiter = synchronized(lock) {
				val w = pendingDeferred
				pendingDeferred = null
				running = false
				w
			}
			waiter?.let { accumulatedWaiters.add(it) }

			if (e is CancellationException) {
				logger.debug {
					"Leader cancelled after $iteration iteration(s) — " +
							"notifying ${accumulatedWaiters.size} waiter(s) with non-cancellation wrapper"
				}
				val wrapper = LeaderCancelledException(
					"Discovery leader was cancelled — retry by reopening the dialog", e,
				)
				accumulatedWaiters.forEach { it.completeExceptionally(wrapper) }
			} else {
				logger.debug(e) {
					"Leader probe failed after $iteration iteration(s) — " +
							"notifying ${accumulatedWaiters.size} waiter(s)"
				}
				accumulatedWaiters.forEach { it.completeExceptionally(e) }
			}
			throw e
		}
	}

	private companion object {
		val logger = KotlinLogging.logger {}
	}
}

