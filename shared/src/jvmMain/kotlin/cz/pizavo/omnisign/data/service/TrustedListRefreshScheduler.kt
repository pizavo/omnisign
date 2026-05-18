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
	 * Warm every distinct trusted source once, then online-refresh them all as a
	 * coherent set on each interval tick until the calling coroutine is cancelled.
	 *
	 * Suspends for the lifetime of the host; launch it in a background scope. The
	 * shared refresh executor is released when the coroutine completes.
	 */
	suspend fun run() {
		try {
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

			while (true) {
				coroutineContext.ensureActive()
				factory.refreshTrustedSources()
				delay(intervalMillis)
			}
		} finally {
			factory.shutdownTrustedSources()
		}
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
	}
}
