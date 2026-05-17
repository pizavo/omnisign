package cz.pizavo.omnisign.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide "a PKCS#11 discovery cycle is in flight" signal, reference-counted across
 * every producer that can populate the probe cache.
 *
 * Unified across:
 * - [Pkcs11WarmupService] at application start.
 * - [Pkcs11CacheInvalidator] background rediscovery launched by a PC/SC reader-state event.
 * - Direct [Pkcs11Discoverer.discoverTokens] calls.
 *
 * Held as a single shared instance (Koin `single`) so every producer and consumer observes
 * one coherent flag.  Consumers that want a passive cache read should
 * `discoveryRunning.filter { !it }.first()` before [Pkcs11Discoverer.getCachedTokens] so the
 * cache is fully populated by any in-flight producer before being read.
 */
class Pkcs11DiscoverySignal {

	/**
	 * Reference count of concurrently in-flight discovery cycles.  Drives transitions of
	 * [discoveryRunning]: `0 → 1` flips the flag `true`, `1 → 0` flips it `false`.
	 * Intermediate increments leave the flag at `true` so concurrent callers don't briefly
	 * observe a false gap between back-to-back cycles.
	 */
	private val activeDiscoveryCount = AtomicInteger(0)

	/**
	 * Backing mutable flow for [discoveryRunning].  Modified only via [beginDiscovery] /
	 * [endDiscovery] so the refcount and the flag stay coherent.
	 */
	private val _discoveryRunning = MutableStateFlow(false)

	/**
	 * `true` while at least one discovery cycle is in flight, `false` when none is.
	 *
	 * Initial value is `false` because warmup is always launched at bootstrap, well before
	 * any UI dialog can read this — warmup's first action is [beginDiscovery], which flips
	 * the flag synchronously on the warmup coroutine before any consumer can observe it.
	 * Falling back to `false` (rather than an initial `true` that warmup later clears)
	 * avoids the "dialog spinner forever" failure mode if warmup ever fails to launch.
	 */
	val discoveryRunning: StateFlow<Boolean> = _discoveryRunning.asStateFlow()

	/**
	 * Increment the discovery refcount, flipping [discoveryRunning] to `true` if this is the
	 * first in-flight cycle.
	 *
	 * Producers that don't go through [Pkcs11Discoverer.discoverTokens] (notably
	 * [Pkcs11WarmupService], which runs its own subprocess loop and writes through to the
	 * probe cache) must still call this so their in-progress state is published.  Pair every
	 * call with [endDiscovery] in a `finally` block to avoid leaking the flag at `true`.
	 */
	fun beginDiscovery() {
		if (activeDiscoveryCount.incrementAndGet() == 1) _discoveryRunning.value = true
	}

	/**
	 * Decrement the discovery refcount, flipping [discoveryRunning] to `false` when the last
	 * in-flight cycle ends.  See [beginDiscovery] for the usage contract.
	 */
	fun endDiscovery() {
		if (activeDiscoveryCount.decrementAndGet() == 0) _discoveryRunning.value = false
	}
}
