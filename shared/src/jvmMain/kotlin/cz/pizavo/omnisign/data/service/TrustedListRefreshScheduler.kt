package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.data.repository.DssServiceFactory
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Drives the single, process-global trusted-list refresh cycle.
 *
 * Started once per long-running host (desktop, server) on a background coroutine.
 * It is deliberately never started by the one-shot CLI, which relies on the
 * registry's offline-first lazy path instead.
 *
 * The cycle is coherent: every retained trusted source is refreshed together on
 * one heartbeat derived from the process-global
 * `GlobalConfig.trustedListRefreshIntervalHours`, so a lazily-acquired profile
 * list can never drift out of phase with the shared EU LOTL.
 *
 * @property factory The shared [DssServiceFactory] whose [TrustedSourceRegistry]
 *   is warmed and refreshed.
 * @property configRepository Source of the active configuration: the global
 *   refresh interval and the set of profiles to warm.
 */
class TrustedListRefreshScheduler(
	private val factory: DssServiceFactory,
	private val configRepository: ConfigRepository,
) {

	/**
	 * Warm every distinct trusted source once (offline-first), then online-refresh
	 * them all as a coherent set on each interval tick until the calling coroutine
	 * is cancelled.
	 *
	 * The first online refresh is deferred a full interval on purpose: the warmup
	 * has just loaded the sources, so refreshing immediately would only re-process
	 * (re-canonicalize/re-digest) data we already have, adding a redundant CPU
	 * pass at the worst moment — startup.
	 *
	 * The exception is failure: when a source did not load (offline at startup, or a
	 * temporarily unreachable source), the next refresh is retried after a short
	 * [RETRY_INTERVAL_MS] for up to [MAX_FAST_RETRIES] attempts,
	 * so a reconnected host recovers within minutes instead of a full interval. A
	 * source that stays unreachable then falls back to the normal cadence rather than
	 * being hammered; the user can still force a refresh in the meantime.
	 *
	 * Suspends for the lifetime of the host; launch it in a background scope. The
	 * shared refresh executor is released when the coroutine completes.
	 */
	suspend fun run() {
		try {
			val configuredInterval = prime()
			var fastRetries = 0
			var incompleteTrust = factory.hasIncompleteTrustedSources()
			while (true) {
				delay(nextRefreshDelay(incompleteTrust, fastRetries, configuredInterval))
				coroutineContext.ensureActive()
				factory.refreshTrustedSources()
				incompleteTrust = factory.hasIncompleteTrustedSources()
				fastRetries = if (incompleteTrust) fastRetries + 1 else 0
			}
		} finally {
			factory.shutdownTrustedSources()
		}
	}

	/**
	 * The delay before the next refresh: a short [RETRY_INTERVAL_MS] (never longer
	 * than [configuredInterval]) while trust is [incompleteTrust] and fewer than
	 * [MAX_FAST_RETRIES] fast retries have run — so a transient failure recovers within
	 * minutes — otherwise the full [configuredInterval], so a persistently unreachable source is
	 * not hammered.
	 *
	 * @param incompleteTrust Whether a retained source currently holds no trust (failed to load).
	 * @param fastRetries How many consecutive fast retries have already run without recovering.
	 * @param configuredInterval The normal refresh interval, in milliseconds.
	 */
	internal fun nextRefreshDelay(incompleteTrust: Boolean, fastRetries: Int, configuredInterval: Long): Long =
		if (incompleteTrust && fastRetries < MAX_FAST_RETRIES) {
			RETRY_INTERVAL_MS.coerceAtMost(configuredInterval)
		} else {
			configuredInterval
		}

	/**
	 * Trigger an immediate, out-of-cycle **hard** refresh of every distinct
	 * trusted source and suspend until it completes.
	 *
	 * Used by the desktop "Refresh now" control and the CLI `config tl refresh`
	 * command. Unlike the scheduled cycle this forces a real network re-download
	 * regardless of cache freshness (the user explicitly asked for fresh data),
	 * after priming so a freshly added profile's lists are picked up even if they
	 * were never warmed.
	 */
	suspend fun refreshNow() {
		logger.info { "Manual hard trusted-list refresh requested" }
		try {
			prime()
			factory.forceRefreshTrustedSources()
			logger.info { "Manual hard trusted-list refresh completed" }
		} catch (e: Exception) {
			logger.error(e) { "Manual hard trusted-list refresh failed" }
			throw e
		}
	}

	/**
	 * Apply the configured refresh interval and ensure every distinct trusted
	 * source is acquired (offline-first). Shared by [run] and [refreshNow].
	 *
	 * @return the resolved refresh interval in milliseconds.
	 */
	private suspend fun prime(): Long {
		val appConfig = configRepository.getCurrentConfig()
		val intervalHours = appConfig.global.trustedListRefreshIntervalHours.coerceAtLeast(MIN_INTERVAL_HOURS)
		val intervalMillis = intervalHours * MILLIS_PER_HOUR
		factory.configureTrustedListRefreshInterval(intervalMillis)

		val (useEuLotl, customTls) = collectDistinctSources(appConfig)
		logger.info {
			"Warming trusted lists (EU LOTL=$useEuLotl, ${customTls.size} custom), " +
					"refresh every ${intervalHours}h"
		}
		factory.warmUpTrustedSources(useEuLotl, customTls)
		return intervalMillis
	}

	/**
	 * Resolve the no-profile context and every named profile, returning whether
	 * any resolved context uses the EU LOTL and the union of all custom trusted
	 * lists across them. Profiles that fail to resolve are skipped — a broken
	 * profile must not abort warmup for the rest.
	 *
	 * The registry deduplicates custom lists by identity, so returning the raw
	 * union (possibly with repeats) is intentional and harmless.
	 */
	private fun collectDistinctSources(
		appConfig: AppConfig,
	): Pair<Boolean, List<CustomTrustedListConfig>> {
		val contexts = buildList {
			add(null)
			addAll(appConfig.profiles.values)
		}
		var useEuLotl = false
		val customTls = mutableListOf<CustomTrustedListConfig>()
		for (profile in contexts) {
			val resolved = ResolvedConfig.resolve(appConfig.global, profile, operationOverrides = null)
				.getOrNull() ?: continue
			if (resolved.validation.useEuLotl) useEuLotl = true
			customTls += resolved.validation.customTrustedLists
		}
		return useEuLotl to customTls
	}

	private companion object {
		const val MIN_INTERVAL_HOURS = 1L
		const val MILLIS_PER_HOUR = 60L * 60L * 1000L

		/** Short interval between refreshes while a source is failing to load, so a reconnect recovers fast. */
		const val RETRY_INTERVAL_MS = 5L * 60L * 1000L

		/** Consecutive fast retries after which a still-failing source falls back to the normal cadence. */
		const val MAX_FAST_RETRIES = 6
	}
}
