package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.tsl.source.TLSource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Process-wide registry of retained, identity-keyed DSS trusted-list sources.
 *
 * One retained [TLValidationJob] per distinct [TrustedSourceId] (one shared EU
 * LOTL, one per custom list). Retaining the job is the core optimization: DSS
 * keys its parse/validation cache by URL inside the job, so a re-refresh skips
 * re-parsing and re-verifying any list whose downloaded bytes are unchanged.
 *
 * Locking is **per entry**, not registry-wide: each [RetainedTl] has its own
 * lock, and acquisition/refresh of one source never blocks a validation that
 * needs a different source. This is what makes the validation panel's scoped
 * wait truthful — it contends only on the ids its config needs. The trade-off
 * is that a refresh cycle updates entries sequentially, so a validation running
 * mid-cycle may observe one entry refreshed and another not yet; the cadence is
 * still a single shared interval (no per-source TTL drift).
 *
 * Every acquire/refresh is bracketed with [TrustedListRefreshSignal] begin/end
 * for its id so consumers can scope their wait precisely.
 */
class TrustedSourceRegistry(
	private val signal: TrustedListRefreshSignal = TrustedListRefreshSignal(),
) {

	/**
	 * A retained trusted-list source: the long-lived [TLValidationJob] (whose
	 * cache makes scheduled re-refreshes cheap), the stable
	 * [TrustedListsCertificateSource] it populates, its own lock, the most recent
	 * loading warnings, and [buildJob] — the factory used to spin up a *fresh*
	 * job (empty cache) for a user-initiated hard refresh that must genuinely
	 * re-download and re-parse.
	 */
	private class RetainedTl(
		val id: TrustedSourceId,
		val job: TLValidationJob,
		val source: TrustedListsCertificateSource,
		val buildJob: (TrustedListsCertificateSource, FileCacheDataLoader) -> TLValidationJob,
	) {
		val lock = ReentrantLock()

		@Volatile
		var initialized = false

		@Volatile
		var warnings: List<String> = emptyList()
	}

	/**
	 * Re-download threshold for the online [FileCacheDataLoader], in milliseconds.
	 * Defaults to [DssServiceFactory.TL_CACHE_EXPIRATION_MS]; the refresh scheduler
	 * sets it from the process-global `trustedListRefreshIntervalHours` setting.
	 */
	@Volatile
	var cacheExpirationMillis: Long = DssServiceFactory.TL_CACHE_EXPIRATION_MS

	private val entries = ConcurrentHashMap<TrustedSourceId, RetainedTl>()

	/**
	 * Shared, bounded, daemon-threaded executor for every retained job, capping
	 * the peak thread/CPU spike of the unavoidable changed-content refresh.
	 * Lazily created so configs/tests that build no job never allocate it.
	 */
	private val executor: ExecutorService by lazy {
		val poolSize = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
		Executors.newFixedThreadPool(poolSize) { runnable ->
			Thread(runnable, "omnisign-tl-refresh").apply { isDaemon = true }
		}
	}

	/**
	 * Select the trusted sources [config] requires, acquiring (and lazily warming)
	 * each one, and wire them into [cv] as a single aggregated trusted-cert source.
	 *
	 * @return the union of loading warnings for the sources this config uses, so a
	 *   profile is never shown warnings about another profile's lists.
	 */
	fun composeInto(cv: CommonCertificateVerifier, config: ResolvedConfig): List<String> {
		val validation = config.validation
		val sources = mutableListOf<CertificateSource>()
		val warnings = mutableListOf<String>()

		if (validation.useEuLotl) {
			val entry = acquireLotl()
			sources += entry.source
			warnings += entry.warnings
		}

		for (tl in validation.customTrustedLists) {
			val entry = acquireCustom(tl)
			sources += entry.source
			warnings += entry.warnings
		}

		DssServiceFactory.buildDirectTrustedCertSource(validation.trustedCertificates)
			?.let { sources += it }

		if (sources.isNotEmpty()) {
			cv.setTrustedCertSources(*sources.toTypedArray())
		}
		return warnings
	}

	/**
	 * Ensure the entries for [useEuLotl] and [customTls] exist and are loaded.
	 * Used by the startup warmup so the first validation hits warm sources.
	 */
	fun warmUp(useEuLotl: Boolean, customTls: List<CustomTrustedListConfig>) {
		if (useEuLotl) acquireLotl()
		customTls.forEach { acquireCustom(it) }
	}

	/**
	 * Online-refresh every retained source on the global refresh cycle. Each
	 * entry is refreshed under its own lock and bracketed with the refresh
	 * signal, so a validation needing a different source is not blocked.
	 */
	fun refreshAll() {
		entries.values.forEach { entry ->
			entry.lock.withLock {
				signal.begin(entry.id)
				try {
					refreshOnline(entry, entry.job)
				} finally {
					signal.end(entry.id)
				}
			}
		}
		if (entries.isNotEmpty()) signal.markRefreshed(Clock.System.now())
	}

	/**
	 * Hard-refresh every retained source.
	 *
	 * Backs the user-initiated "Refresh now" (desktop) and `config tl refresh`
	 * (CLI). Unlike the cache-gated scheduled [refreshAll], this builds a **fresh**
	 * [TLValidationJob] per entry with an expiration-zero online loader. A fresh
	 * job has an empty in-memory cache, so DSS cannot short-circuit on unchanged
	 * digests — it is forced to actually re-download, re-parse and re-verify every
	 * list, then synchronize the result into the entry's stable source. Genuinely
	 * heavy by design; that is what the user asked for. The retained job and its
	 * cheap cache are left untouched for the scheduled cycle.
	 */
	fun forceRefreshAll() {
		logger.info { "Hard refresh: ${entries.size} retained trusted source(s) to re-download" }
		entries.values.forEach { entry ->
			entry.lock.withLock {
				signal.begin(entry.id)
				try {
					logger.info { "Hard-refreshing ${entry.id} (fresh job, forced network re-download)" }
					val freshLoader = newOnlineLoader().apply {
						setCacheExpirationTime(FORCE_REFRESH_EXPIRATION_MS)
					}
					val freshJob = entry.buildJob(entry.source, freshLoader)
					refreshOnline(entry, freshJob)
				} finally {
					signal.end(entry.id)
				}
			}
		}
		if (entries.isNotEmpty()) signal.markRefreshed(Clock.System.now())
	}

	/**
	 * Shut the shared executor down. Invoked when a long-running host stops; a
	 * one-shot CLI relies on the daemon threads instead and need not call this.
	 */
	fun shutdown() {
		if (entries.isNotEmpty()) executor.shutdownNow()
	}

	/** Acquire the shared EU LOTL entry, building + offline-first warming once. */
	private fun acquireLotl(): RetainedTl = acquire(TrustedSourceId.EuLotl) { source, onlineLoader ->
		newJob(source, onlineLoader).apply {
			setListOfTrustedListSources(
				LOTLSource().apply {
					url = DssServiceFactory.EU_LOTL_URL
					certificateSource = DssServiceFactory.buildOjCertificateSource()
					isPivotSupport = true
				}
			)
		}
	}

	/** Acquire the retained entry for a custom trusted list by its identity. */
	private fun acquireCustom(config: CustomTrustedListConfig): RetainedTl =
		acquire(TrustedSourceId.CustomList(config.source, config.signingCertPath)) { source, onlineLoader ->
			newJob(source, onlineLoader).apply {
				setTrustedListSources(
					TLSource().apply {
						url = config.source
						config.signingCertPath?.let {
							certificateSource = DssServiceFactory.buildCertSourceFromFile(it)
						}
					}
				)
			}
		}

	/**
	 * Get-or-create the retained entry for [id]. The cheap shell (source + job,
	 * no network) is created atomically per id; the slow offline-first warm runs
	 * once under the entry's own lock, bracketed with the refresh signal so the
	 * first lazy load is visible to scoped waiters.
	 */
	private fun acquire(
		id: TrustedSourceId,
		buildJob: (TrustedListsCertificateSource, FileCacheDataLoader) -> TLValidationJob,
	): RetainedTl {
		entries[id]?.let { if (it.initialized) return it }
		val entry = entries.computeIfAbsent(id) { key ->
			val source = TrustedListsCertificateSource()
			val onlineLoader = newOnlineLoader()
			RetainedTl(key, buildJob(source, onlineLoader), source, buildJob)
		}
		entry.lock.withLock {
			if (!entry.initialized) {
				signal.begin(id)
				try {
					refreshOfflineFirst(entry)
				} finally {
					signal.end(id)
				}
				entry.initialized = true
				signal.markRefreshed(Clock.System.now())
			}
		}
		return entry
	}

	/**
	 * Build an online [FileCacheDataLoader] over the persistent cache directory.
	 *
	 * Used for the retained job's cache-gated loader, and (with expiration forced
	 * to zero) for the throwaway loader of a [forceRefreshAll] hard refresh.
	 */
	private fun newOnlineLoader(): FileCacheDataLoader {
		val cacheDir = DssServiceFactory.tlCacheDir().also { it.mkdirs() }
		return FileCacheDataLoader().apply {
			setCacheExpirationTime(cacheExpirationMillis)
			setDataLoader(
				CommonsDataLoader().apply {
					timeoutConnection = DssServiceFactory.TL_FETCH_TIMEOUT_MS
					timeoutSocket = DssServiceFactory.TL_FETCH_TIMEOUT_MS
				}
			)
			setFileCacheDirectory(cacheDir)
		}
	}

	/**
	 * Build a retained [TLValidationJob] bound to [source] with the shared
	 * executor, a never-expiring offline cache, and the supplied [onlineLoader].
	 */
	private fun newJob(
		source: TrustedListsCertificateSource,
		onlineLoader: FileCacheDataLoader,
	): TLValidationJob {
		val cacheDir = DssServiceFactory.tlCacheDir().also { it.mkdirs() }
		return TLValidationJob().apply {
			setExecutorService(executor)
			setTrustedListCertificateSource(source)
			setOfflineDataLoader(
				FileCacheDataLoader().apply {
					setCacheExpirationTime(DssServiceFactory.CACHE_NEVER_EXPIRE)
					setFileCacheDirectory(cacheDir)
				}
			)
			setOnlineDataLoader(onlineLoader)
		}
	}

	/**
	 * Offline-first warm: serve instantly from the on-disk cache (no network, no
	 * unreachable-host stalls); fall back to a synchronous online refresh only
	 * when the cache is genuinely cold (first ever run). The scheduled
	 * [refreshAll] keeps the retained source fresh afterwards.
	 */
	private fun refreshOfflineFirst(entry: RetainedTl) {
		runCatching { entry.job.offlineRefresh() }
			.onFailure { logger.debug(it) { "Offline TL refresh failed; trying online" } }
		if (entry.source.certificates.isEmpty()) {
			runCatching { entry.job.onlineRefresh() }
				.onFailure { logger.warn(it) { "Online TL refresh failed; trust may be incomplete" } }
		}
		entry.warnings = DssServiceFactory.collectTlWarnings(entry.job.summary)
	}

	/**
	 * Online-refresh [job] (the retained job for the scheduled cycle, or a fresh
	 * job for a forced hard refresh), then refresh [entry]'s warnings from it.
	 */
	private fun refreshOnline(entry: RetainedTl, job: TLValidationJob) {
		val elapsed = kotlin.system.measureTimeMillis {
			runCatching { job.onlineRefresh() }
				.onFailure { logger.warn(it) { "Online TL refresh of ${entry.id} failed; serving last good trust" } }
		}
		logger.info { "onlineRefresh(${entry.id}) took ${elapsed}ms" }
		entry.warnings = DssServiceFactory.collectTlWarnings(job.summary)
	}

	/** Registry constants. */
	private companion object {
		/**
		 * Cache-expiration value that forces [FileCacheDataLoader] to treat every
		 * cached file as expired (`now - lastModified >= 0` always holds), so a
		 * hard refresh always re-downloads.
		 */
		const val FORCE_REFRESH_EXPIRATION_MS = 0L
	}
}
