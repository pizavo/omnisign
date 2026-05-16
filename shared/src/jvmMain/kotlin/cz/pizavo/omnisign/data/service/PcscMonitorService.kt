package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CardTerminals
import javax.smartcardio.TerminalFactory

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
 */
class PcscMonitorService(
	private val terminalsProvider: () -> CardTerminals? = { defaultTerminals() },
	private val pollTimeoutMillis: Long = DEFAULT_POLL_TIMEOUT_MILLIS,
) {

	private val supervisor = SupervisorJob()
	private val scope = CoroutineScope(Dispatchers.IO + supervisor)

	private val _events = MutableSharedFlow<PcscEvent>(
		replay = 0,
		extraBufferCapacity = EVENTS_BUFFER_CAPACITY,
	)

	@Volatile
	private var watcher: Job? = null

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
		val terminals = runCatching { terminalsProvider() }
			.onFailure { logger.debug(it) { "PC/SC TerminalFactory unavailable" } }
			.getOrNull()
			?: return emptyList()

		return runCatching { terminals.list().map { it.snapshot() } }
			.onFailure { logger.debug(it) { "PC/SC reader enumeration failed" } }
			.getOrDefault(emptyList())
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
	 */
	private suspend fun runWatchLoop(terminals: CardTerminals) {
		var previous = snapshot(terminals)
		try {
			while (true) {
				val changed = runCatching { terminals.waitForChange(pollTimeoutMillis) }
					.getOrDefault(false)
				val current = snapshot(terminals)
				if (changed || current != previous) {
					emitDiff(previous, current)
					previous = current
				}
			}
		} catch (e: Throwable) {
			logger.warn(e) { "PC/SC watcher loop terminated; PCSCEvent flow will no longer emit" }
		}
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
