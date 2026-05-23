package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CardTerminals
import javax.smartcardio.TerminalFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cross-platform PC/SC reader-state observer built on `javax.smartcardio`.
 *
 * Two responsibilities:
 *
 * 1. **Synchronous snapshot** via [currentReaders] — used by [Pkcs11DiagnosticsService] and
 *    any caller that just needs to know which readers exist *right now*.
 * 2. **Asynchronous event stream** via [events] — a hot [SharedFlow] backed by a daemon
 *    coroutine that loops on [CardTerminals.waitForChange] and emits [PcscEvent] values for
 *    every observed change.
 *
 * The service silently no-ops when the platform PC/SC stack is unreachable (e.g. `pcscd` is
 * not running on Linux, or no smart-card driver is installed).  In that case [currentReaders]
 * returns an empty list and [events] emits nothing — diagnostics still render gracefully.
 *
 * The event coroutine runs on a [SupervisorJob] tied to a private scope so individual loop
 * failures don't propagate up; the loop logs the failure and exits, leaving [currentReaders]
 * functional.  Restarting the loop is not currently supported (the typical failure mode is
 * `pcscd` going down for the rest of the session).
 *
 * @property terminalsProvider Hook used by tests to inject a fake [CardTerminals].  In
 *   production it defaults to [TerminalFactory.getDefault].
 * @property pollTimeoutMillis How long [CardTerminals.waitForChange] blocks per iteration
 *   before re-polling.  Short enough to react to shutdown promptly; long enough to avoid
 *   busy-spinning.  Defaults to 30 seconds.
 * @property recovery Recovers the JDK process-wide PC/SC context after the
 *   `sun.security.smartcardio` stale-context defect so [currentReaders] and the watch
 *   loop resume within the same JVM session instead of degrading until restart.  See
 *   [PcscContextRecovery].
 * @property scope Coroutine scope the background watcher runs on.  Defaults to a private
 *   [SupervisorJob]-backed [Dispatchers.IO] scope so collector failures never propagate
 *   and the daemon threads don't keep the JVM alive; tests override with a `TestScope`
 *   so the no-readers poll can be advanced in virtual time.
 */
class PcscMonitorService(
	private val terminalsProvider: () -> CardTerminals? = { defaultTerminals() },
	private val pollTimeoutMillis: Long = DEFAULT_POLL_TIMEOUT_MILLIS,
	private val recovery: PcscContextRecovery = PcscContextRecovery(),
	private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : AutoCloseable {

	private val _events = MutableSharedFlow<PcscEvent>(
		replay = 0,
		extraBufferCapacity = EVENTS_BUFFER_CAPACITY,
	)

	@Volatile
	private var watcher: Job? = null

	/**
	 * Cancel the background watcher coroutine and any pending [waitForChange] iteration.
	 *
	 * Used by hosts with a bounded lifecycle (notably the Ktor server's `testApplication`
	 * teardown) to stop the watcher when the application stops, instead of leaking the
	 * coroutine across test boundaries. After [close] the service can no longer emit
	 * events — [events] subscribers see no further values and the snapshot path
	 * ([currentReaders]) still works because it does not depend on the watcher.
	 *
	 * Production daemons (desktop, long-running server) call this at JVM shutdown so the
	 * background coroutine is cleanly cancelled rather than relying on the daemon-thread
	 * default. Safe to call multiple times; cancelling an already-cancelled scope is a
	 * no-op.
	 */
	override fun close() {
		scope.cancel()
	}

	/**
	 * Hot stream of reader-state changes.  Subscribers automatically start the background
	 * watcher on first collection; collecting again from a second site shares the same
	 * watcher.  No replay buffer — late subscribers see only events emitted after they
	 * subscribed.
	 */
	val events: SharedFlow<PcscEvent>
		get() {
			ensureWatcherRunning()
			return _events.asSharedFlow()
		}

	/**
	 * Synchronous snapshot of every reader visible to the PC/SC subsystem right now.
	 *
	 * Returns an empty list when the PC/SC stack is unreachable or no readers are present.
	 * Never throws — diagnostic and UI callers can rely on this for graceful rendering.
	 */
	fun currentReaders(): List<PcscReader> {
		return runCatching {
			withStaleContextRecovery {
				val terminals = terminalsProvider()
				terminals?.list()?.map { it.snapshot() } ?: emptyList()
			}
		}.onFailure { logger.debug(it) { "PC/SC reader enumeration failed" } }
			.getOrDefault(emptyList())
	}

	/**
	 * Run [block]; if it fails with the JDK stale-PC/SC-context signature, reset the
	 * context via [recovery] and run [block] exactly once more.  Any other failure, or
	 * one that survives the reset, propagates to the caller's own handling.
	 *
	 * @param block The `javax.smartcardio` operation to run, retryable after a reset.
	 * @return [block]'s result, from the first attempt or the post-reset retry.
	 */
	private fun <T> withStaleContextRecovery(block: () -> T): T {
		return try {
			block()
		} catch (e: Exception) {
			if (recovery.isStaleContext(e) && recovery.resetContext()) {
				logger.info { "PC/SC stale context reset — retrying the failed reader operation once" }
				block()
			} else {
				throw e
			}
		}
	}

	/**
	 * Lazily starts a single background coroutine that loops on [CardTerminals.waitForChange]
	 * and emits [PcscEvent] values via [_events].  Idempotent — repeated calls reuse the
	 * existing watcher.  When [terminalsProvider] returns `null` (no PC/SC stack) the watcher
	 * is not started and [events] simply never emits.
	 */
	private fun ensureWatcherRunning() {
		val current = watcher
		if (current != null && current.isActive) return
		synchronized(this) {
			val rechecked = watcher
			if (rechecked != null && rechecked.isActive) return
			val terminals = runCatching { terminalsProvider() }.getOrNull() ?: run {
				logger.warn { "PC/SC monitor: no terminal factory available — watcher will NOT start (smart-card hot-insert events will not fire)" }
				return
			}
			val initialReaders = runCatching { terminals.list() }.getOrDefault(emptyList())
			logger.info { "PC/SC monitor: watcher starting — initial reader count=${initialReaders.size}, names=${initialReaders.map { it.name }}" }
			watcher = scope.launch { runWatchLoop(terminals) }
		}
	}

	/**
	 * Watch loop: poll the current state of every terminal, compare against the prior
	 * snapshot, emit diff events.  Uses [CardTerminals.waitForChange] when supported so
	 * the loop blocks until the OS reports a change rather than busy-polling.
	 *
	 * [CardTerminals.waitForChange] failures are classified into three cases so the
	 * watcher survives the conditions that previously killed it:
	 *
	 * - **Stale context** (`SCARD_E_NO_SERVICE` / `SCARD_E_SERVICE_STOPPED` /
	 *   `SCARD_E_INVALID_HANDLE`): the JDK's process-wide context is dead.  Reset it via
	 *   [recovery], rebuild [CardTerminals], then **emit any reader-state change that
	 *   occurred during the stale window** before resuming — a hot-plug there is
	 *   invisible to [CardTerminals.waitForChange], so without this it would be silently
	 *   absorbed into the new baseline and never trigger rediscovery.
	 * - **No readers** ([PcscContextRecovery.NO_READERS_AVAILABLE]): the context is
	 *   healthy but the reader set is empty, so [CardTerminals.waitForChange] cannot
	 *   block on it.  Degrade to a fixed-interval snapshot poll ([emitSnapshotDiff] +
	 *   [NO_READERS_POLL_INTERVAL_MILLIS]) so a hot-insert from a no-reader start is
	 *   still detected; the next loop turn resumes the event-driven wait once a reader
	 *   exists, or routes to the stale path if the service has since stopped.
	 * - **Anything else**: terminate the loop as before.
	 *
	 * @param initialTerminals The terminals acquired at watcher start; rebuilt in place
	 *   after a stale-context recovery.
	 */
	private suspend fun runWatchLoop(initialTerminals: CardTerminals) {
		var terminals = initialTerminals
		var previous = snapshot(terminals)
		try {
			while (true) {
				val poll = runCatching { terminals.waitForChange(pollTimeoutMillis) }
				val failure = poll.exceptionOrNull()
				when {
					failure != null && recovery.isStaleContext(failure) -> {
						logger.info { "PC/SC watcher hit a stale context ($failure) — resetting and rebuilding the reader watcher" }
						terminals = recoverTerminals() ?: run {
							logger.warn { "PC/SC watcher could not recover the context; reader-state events will not resume until restart" }
							return
						}
						val postReset = snapshot(terminals)
						if (postReset != previous) {
							logStaleResetRecovery(previous, postReset)
							emitDiff(previous, postReset)
						}
						previous = postReset
					}

					failure != null && recovery.causeChainContains(failure, PcscContextRecovery.NO_READERS_AVAILABLE) -> {
						previous = emitSnapshotDiff(terminals, previous)
						delay(NO_READERS_POLL_INTERVAL_MILLIS)
					}

					failure != null -> {
						logger.warn(failure) { "PC/SC watcher loop terminated; PCSCEvent flow will no longer emit" }
						return
					}

					else -> {
						val current = snapshot(terminals)
						if (poll.getOrDefault(false) || current != previous) {
							emitDiff(previous, current)
							previous = current
						}
					}
				}
			}
		} catch (e: Throwable) {
			logger.warn(e) { "PC/SC watcher loop terminated; PCSCEvent flow will no longer emit" }
		}
	}

	/**
	 * Take a fresh [snapshot] and emit a [PcscEvent] for every difference from
	 * [previous].
	 *
	 * Used by the no-readers polling branch of [runWatchLoop]: while the reader set is
	 * empty the event-driven [CardTerminals.waitForChange] cannot fire, so a hot-insert
	 * is observed only by comparing successive snapshots here.
	 *
	 * @param terminals The terminals to snapshot.
	 * @param previous The prior snapshot to diff against.
	 * @return the snapshot just taken, to become the loop's new baseline.
	 */
	private suspend fun emitSnapshotDiff(
		terminals: CardTerminals,
		previous: Map<String, PcscReader>,
	): Map<String, PcscReader> {
		val current = snapshot(terminals)
		if (current != previous) emitDiff(previous, current)
		return current
	}

	/**
	 * Log, at INFO, the reader-set change that the stale-context recovery branch is
	 * about to re-emit.
	 *
	 * A non-empty change here means a card/reader was inserted or removed *while the JDK
	 * PC/SC context was stale*: [CardTerminals.waitForChange] cannot report a change
	 * that occurred during its own failure, so the stale-recovery branch detects it by
	 * snapshot diff and emits the corresponding [PcscEvent] itself (driving
	 * [Pkcs11CacheInvalidator] rediscovery) instead of silently absorbing it into the
	 * new baseline.  Logged so this recovery is observable in the field.  The caller
	 * invokes this only when the diff is non-empty.
	 *
	 * @param before The reader snapshot from before the stale-context reset.
	 * @param after The reader snapshot taken immediately after the reset rebuilt the
	 *   terminals.
	 */
	private fun logStaleResetRecovery(
		before: Map<String, PcscReader>,
		after: Map<String, PcscReader>,
	) {
		val appeared = after.keys - before.keys
		val vanished = before.keys - after.keys
		val cardChanged = after.keys.intersect(before.keys)
			.filter { before.getValue(it).cardPresent != after.getValue(it).cardPresent }
		logger.info {
			"PC/SC stale-context reset recovered a reader change that occurred during the " +
				"stale window — appeared=$appeared vanished=$vanished cardStateChanged=$cardChanged; " +
				"emitting the event now so rediscovery runs without a manual rescan"
		}
	}

	/**
	 * Reset the stale PC/SC context and re-acquire a fresh [CardTerminals], pausing
	 * briefly first so a genuinely-down smart-card service cannot spin the watch loop
	 * hot.
	 *
	 * @return the rebuilt terminals, or `null` when the context could not be reset or no
	 *   terminals factory is available — the watcher then stops until restart.
	 */
	private suspend fun recoverTerminals(): CardTerminals? {
		if (!recovery.resetContext()) return null
		delay(RECOVERY_BACKOFF_MILLIS.milliseconds)
		return runCatching { terminalsProvider() }.getOrNull()
	}

	/**
	 * Read the current snapshot of every reader.  Returns an empty map when enumeration
	 * fails so the diff logic still works (it just looks like every reader disappeared,
	 * which is the right semantic in that case).
	 */
	private fun snapshot(terminals: CardTerminals): Map<String, PcscReader> {
		return runCatching {
			terminals.list().associate { it.name to it.snapshot() }
		}.getOrDefault(emptyMap())
	}

	/**
	 * Emit one [PcscEvent] for each difference between [previous] and [current] snapshots.
	 *
	 * Order: disconnections first (so subscribers see the removed-reader event before any
	 * new one with the same name appears), then connections, then card insertions/removals.
	 */
	private suspend fun emitDiff(previous: Map<String, PcscReader>, current: Map<String, PcscReader>) {
		for (name in previous.keys - current.keys) {
			_events.emit(PcscEvent.ReaderDisconnected(name))
		}
		for (name in current.keys - previous.keys) {
			_events.emit(PcscEvent.ReaderConnected(name))
		}
		for (name in current.keys.intersect(previous.keys)) {
			val before = previous.getValue(name)
			val now = current.getValue(name)
			when {
				!before.cardPresent && now.cardPresent -> _events.emit(PcscEvent.CardInserted(now))
				before.cardPresent && !now.cardPresent -> _events.emit(PcscEvent.CardRemoved(now))
				else -> Unit
			}
		}
	}

	/**
	 * Snapshot a single [CardTerminal]: read its name and card-presence flag.
	 *
	 * Reading the ATR requires opening a connection, which can fail or block on a card in
	 * mute / locked state — so this method tolerates failure and returns `atrHex = null`
	 * rather than propagating.  Card-present detection still works without ATR.
	 */
	private fun CardTerminal.snapshot(): PcscReader {
		val present = runCatching { isCardPresent }.getOrDefault(false)
		val atr = if (present) {
			runCatching {
				val card = connect("*")
				try {
					card.atr.bytes.joinToString("") { "%02X".format(it) }
				} finally {
					runCatching { card.disconnect(false) }
				}
			}.getOrNull()
		} else null
		return PcscReader(name, present, atr)
	}

	private companion object {
		val logger = KotlinLogging.logger {}

		/**
		 * Default poll timeout for [CardTerminals.waitForChange].  Long enough to be a
		 * proper "block until change" without burning CPU; short enough that a clean
		 * shutdown only waits at most this duration before the watcher coroutine notices
		 * cancellation.
		 */
		const val DEFAULT_POLL_TIMEOUT_MILLIS = 30_000L

		/**
		 * Pause after a stale-context reset before re-acquiring terminals in the watch
		 * loop.  Bounds the loop to one recovery attempt every couple of seconds when the
		 * smart-card service is genuinely down, instead of hot-spinning on a context that
		 * cannot yet be re-established.
		 */
		const val RECOVERY_BACKOFF_MILLIS = 2_000L

		/**
		 * Snapshot-poll interval used by [runWatchLoop] while the reader set is empty
		 * (`SCARD_E_NO_READERS_AVAILABLE`), where [CardTerminals.waitForChange] cannot
		 * block.  Short enough that a hot-insert from a cold start is picked up
		 * promptly; long enough not to busy-poll an empty PC/SC subsystem.
		 */
		const val NO_READERS_POLL_INTERVAL_MILLIS = 2_000L

		/**
		 * Buffer capacity on the events flow.  Small because typical reader-state changes
		 * are infrequent and subscribers are expected to be reactive UI code.
		 */
		const val EVENTS_BUFFER_CAPACITY = 16

		/**
		 * Default terminals factory: returns the JDK-shipped [TerminalFactory] terminals
		 * instance, or `null` when the PC/SC stack cannot be queried (no driver, missing
		 * `pcscd`, etc.).  Wrapping in `runCatching` because some JREs throw at this layer
		 * rather than just returning an empty list.
		 */
		fun defaultTerminals(): CardTerminals? = runCatching {
			TerminalFactory.getDefault().terminals()
		}.recoverCatching { e ->
			if (e is CardException) null else throw e
		}.getOrNull()
	}
}
