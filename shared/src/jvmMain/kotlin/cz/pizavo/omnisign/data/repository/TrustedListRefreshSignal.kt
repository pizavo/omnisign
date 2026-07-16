package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

/**
 * Process-wide, per-identity "a trusted-list refresh is in flight" signal.
 *
 * Mirrors the role and refcounting discipline of
 * [cz.pizavo.omnisign.data.service.Pkcs11DiscoverySignal], but scoped: instead of
 * one boolean it tracks *which* [TrustedSourceId]s are currently being acquired
 * or refreshed, so the validation panel can wait only for the sources its active
 * configuration needs while the Settings button reacts to any of them.
 *
 * Refcounted per id so the scheduled cycle and a concurrent manual refresh of the
 * same source don't briefly drop it from [running] between back-to-back passes.
 * Held as a Koin `single` so the registry (producer) and the ViewModels
 * (consumers, via [cz.pizavo.omnisign.domain.port.TrustedListRefreshPort]) share
 * one coherent view.
 */
class TrustedListRefreshSignal {

	private val lock = Any()

	/** Refcount of in-flight refreshes per id; an id is "running" while > 0. */
	private val counts = HashMap<TrustedSourceId, Int>()

	private val _running = MutableStateFlow<Set<TrustedSourceId>>(emptySet())

	/** Ids whose retained job is currently being acquired or refreshed. */
	val running: StateFlow<Set<TrustedSourceId>> = _running.asStateFlow()

	private val _lastRefreshAt = MutableStateFlow<Instant?>(null)

	/** Timestamp of the last successfully completed refresh, or `null`. */
	val lastRefreshAt: StateFlow<Instant?> = _lastRefreshAt.asStateFlow()

	private val _lastFailure = MutableStateFlow<TrustedListRefreshFailure?>(null)

	/**
	 * The most recent refresh that failed to obtain usable trust (e.g. the app was
	 * offline on a cold cache, or a source's download threw), or `null` if none has
	 * failed this process.
	 *
	 * Independent of [lastRefreshAt]: a mixed cycle where one source loads and
	 * another fails advances *both*. A fresh non-null value is the desktop's cue to
	 * surface a failure toast — [TrustedListRefreshFailure.customListName] names the
	 * culprit, and each failure carries a distinct instant so a `StateFlow` observer
	 * re-fires even on a repeated, otherwise-identical failure.
	 */
	val lastFailure: StateFlow<TrustedListRefreshFailure?> = _lastFailure.asStateFlow()

	/**
	 * Mark [id] as refreshing. Pair every call with [end] in a `finally` block so
	 * a failed refresh cannot leak the id into [running] forever.
	 */
	fun begin(id: TrustedSourceId) {
		synchronized(lock) {
			counts[id] = (counts[id] ?: 0) + 1
			_running.value = counts.keys.toSet()
		}
	}

	/**
	 * Drop one in-flight reference for [id], clearing it from [running] when the
	 * last concurrent refresh of that id ends.
	 */
	fun end(id: TrustedSourceId) {
		synchronized(lock) {
			val current = counts[id] ?: 0
			if (current <= 1) counts.remove(id) else counts[id] = current - 1
			_running.value = counts.keys.toSet()
		}
	}

	/**
	 * Record that a refresh completed successfully at [at] — the source ended up
	 * with usable trust — driving the "Last refreshed" indicator.
	 */
	fun markRefreshed(at: Instant) {
		_lastRefreshAt.value = at
	}

	/**
	 * Record that a refresh failed to obtain usable trust, updating [lastFailure]
	 * so the desktop can notify the user and name the [failure]'s source. Does not
	 * touch [lastRefreshAt]; the two outcomes are tracked independently.
	 */
	fun reportFailure(failure: TrustedListRefreshFailure) {
		_lastFailure.value = failure
	}

	private val _trustedListProgress = MutableStateFlow(TrustedListLoadProgress())

	/** Live loading progress across all trusted lists of the in-flight refresh; idle when none. */
	val trustedListProgress: StateFlow<TrustedListLoadProgress> = _trustedListProgress.asStateFlow()

	/** Publish the running (loaded, total) trusted-list task counts of the in-flight refresh. */
	fun reportTrustedListProgress(loaded: Int, total: Int) {
		_trustedListProgress.value = TrustedListLoadProgress(loaded = loaded, total = total)
	}

	/** Clear trusted-list progress back to zero — when a refresh session starts and when it ends. */
	fun resetTrustedListProgress() {
		_trustedListProgress.value = TrustedListLoadProgress()
	}
}
