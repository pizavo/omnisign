package cz.pizavo.omnisign.domain.port

import cz.pizavo.omnisign.domain.model.result.RenewalCheckCacheEntry

/**
 * Caches per-file archival-renewal verdicts so the scheduler can skip the expensive cryptographic
 * validation of files that are unchanged and not yet due for renewal.
 *
 * The JVM implementation persists entries as a small JSON file in the OmniSign config directory.
 * Platforms without filesystem access (e.g. web) may leave this port unregistered.
 */
interface RenewalCheckCache {

	/**
	 * Return the cached entry for [path], or `null` when none exists.
	 */
	fun get(path: String): RenewalCheckCacheEntry?

	/**
	 * Store [entry] for [path], replacing any previous one.
	 */
	fun put(path: String, entry: RenewalCheckCacheEntry)

	/**
	 * Drop any cached entry for [path], forcing a full check on the next run.
	 */
	fun remove(path: String)
}
