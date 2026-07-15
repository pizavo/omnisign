package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
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
	 * loading warnings, [buildJob] — the factory used to spin up a *fresh* job
	 * (empty cache) for a user-initiated hard refresh that must genuinely
	 * re-download and re-parse — and [displayName], the source's human-readable
	 * name for user-facing failure notifications (`null` for the EU LOTL).
	 */
	private class RetainedTl(
		val id: TrustedSourceId,
		val job: TLValidationJob,
		val source: TrustedListsCertificateSource,
		val buildJob: (TrustedListsCertificateSource, FileCacheDataLoader) -> TLValidationJob,
		val displayName: String?,
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
	 * Counting wrapper over [executor] shared by every retained job (EU LOTL and custom lists), so
	 * the desktop can show a determinate "loaded of N" bar covering all trusted lists fetched in a
	 * refresh. It delegates execution to the shared pool (no extra threads) and reports per-list
	 * task progress to [signal]; its counters span a whole refresh via [withProgressSession].
	 */
	private val countingExecutor: CountingExecutorService by lazy {
		CountingExecutorService(executor) { submitted, completed ->
			signal.reportTrustedListProgress(loaded = completed, total = submitted)
		}
	}

	/**
	 * Depth of in-flight refresh operations sharing one progress reading. The outermost operation
	 * zeroes the counters and the last to finish clears them, so a whole cycle accumulates into a
	 * single cumulative bar instead of resetting per source.
	 */
	private val progressSessionDepth = java.util.concurrent.atomic.AtomicInteger(0)

	/**
	 * Failures accumulated during the current refresh session, flushed as one aggregate
	 * [TrustedListRefreshFailure] when the outermost session ends — so a whole cycle (startup
	 * warmup or a refresh) yields a single notification rather than one per failed source.
	 * [sessionLotlFailed] is the EU LOTL flag (prioritised on flush); [sessionFailedCustomNames]
	 * collects the names of the custom lists that failed. Both are thread-safe because concurrent
	 * operations share one process-global session.
	 */
	private val sessionLotlFailed = java.util.concurrent.atomic.AtomicBoolean(false)
	private val sessionFailedCustomNames = java.util.concurrent.ConcurrentLinkedQueue<String>()

	/**
	 * Run [block] as one progress session: zero the shared [countingExecutor] counters and the
	 * failure accumulator when the outermost session starts, and when it ends clear the published
	 * progress and flush any accumulated failures as a single aggregate [TrustedListRefreshFailure]
	 * — so the progress count spans every source the refresh touches and failures collapse to one
	 * notification (EU LOTL prioritised).
	 */
	private fun <T> withProgressSession(block: () -> T): T {
		if (progressSessionDepth.getAndIncrement() == 0) {
			countingExecutor.reset()
			sessionLotlFailed.set(false)
			sessionFailedCustomNames.clear()
		}
		try {
			return block()
		} finally {
			if (progressSessionDepth.decrementAndGet() == 0) {
				signal.resetTrustedListProgress()
				TrustedListRefreshFailure.of(
					lotlFailed = sessionLotlFailed.get(),
					failedCustomNames = sessionFailedCustomNames.toList(),
					at = Clock.System.now(),
				)?.let { signal.reportFailure(it) }
			}
		}
	}

	/**
	 * Record, within the current refresh session, that the source named [displayName]
	 * (`null` = the EU LOTL) failed to load. Flushed as one aggregate by [withProgressSession].
	 */
	private fun accumulateFailure(displayName: String?) {
		if (displayName == null) sessionLotlFailed.set(true) else sessionFailedCustomNames.add(displayName)
	}

	/**
	 * Select the trusted sources [config] requires (the EU LOTL and its custom lists), acquiring
	 * (and lazily warming) each one, and wire them — together with the directly-trusted
	 * [directAnchors] resolved from the app-managed trust store — into [cv] as a single aggregated
	 * trusted-cert source.
	 *
	 * @param directAnchors Directly-trusted certificates (from the trust store) to aggregate
	 *   alongside the trusted lists; empty when the active scope trusts no direct anchors.
	 * @return the union of loading warnings for the sources this config uses, so a
	 *   profile is never shown warnings about another profile's lists.
	 */
	fun composeInto(
		cv: CommonCertificateVerifier,
		config: ResolvedConfig,
		directAnchors: List<ResolvedTrustAnchor> = emptyList(),
	): List<String> = withProgressSession {
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

		DssServiceFactory.buildTrustedCertSource(directAnchors)
			?.let { sources += it }

		if (sources.isNotEmpty()) {
			cv.setTrustedCertSources(*sources.toTypedArray())
		}
		warnings
	}

	/**
	 * Ensure the entries for [useEuLotl] and [customTls] exist and are loaded.
	 * Used by the startup warmup so the first validation hits warm sources.
	 */
	fun warmUp(useEuLotl: Boolean, customTls: List<CustomTrustedListConfig>) = withProgressSession {
		if (useEuLotl) acquireLotl()
		customTls.forEach { acquireCustom(it) }
	}

	/**
	 * Online-refresh every retained source on the global refresh cycle. Each
	 * entry is refreshed under its own lock and bracketed with the refresh
	 * signal, so a validation needing a different source is not blocked.
	 *
	 * The cycle's outcome is recorded on the signal: each source whose download
	 * throws is accumulated (its last good trust keeps being served) and flushed as
	 * one aggregate failure when the session ends, and a fresh "Last refreshed"
	 * timestamp is stamped when at least one source refreshed — both when the cycle
	 * is mixed.
	 */
	fun refreshAll() = withProgressSession {
		var loadedAny = false
		entries.values.forEach { entry ->
			entry.lock.withLock {
				signal.begin(entry.id)
				try {
					if (refreshOnline(entry, entry.job)) loadedAny = true
					else accumulateFailure(entry.displayName)
				} finally {
					signal.end(entry.id)
				}
			}
		}
		if (loadedAny) signal.markRefreshed(Clock.System.now())
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
	fun forceRefreshAll() = withProgressSession {
		logger.info { "Hard refresh: ${entries.size} retained trusted source(s) to re-download" }
		var loadedAny = false
		entries.values.forEach { entry ->
			entry.lock.withLock {
				signal.begin(entry.id)
				try {
					logger.info { "Hard-refreshing ${entry.id} (fresh job, forced network re-download)" }
					val freshLoader = newOnlineLoader().apply {
						setCacheExpirationTime(FORCE_REFRESH_EXPIRATION_MS)
					}
					val freshJob = entry.buildJob(entry.source, freshLoader)
					if (refreshOnline(entry, freshJob)) loadedAny = true
					else accumulateFailure(entry.displayName)
				} finally {
					signal.end(entry.id)
				}
			}
		}
		if (loadedAny) signal.markRefreshed(Clock.System.now())
	}

	/**
	 * Whether any retained trusted source finished loading but holds no certificates —
	 * i.e. it failed to obtain trust (a cold cache while offline, or an unreachable
	 * source URL). The refresh scheduler polls this to retry soon after a failure
	 * instead of waiting a full cycle. `false` when every retained source has trust,
	 * or none is retained.
	 */
	fun hasIncompleteTrust(): Boolean =
		entries.values.any { it.initialized && it.source.certificates.isEmpty() }

	/**
	 * Whether the shared EU LOTL source is retained and currently holds trust. `false` when the
	 * LOTL was never acquired, or when it failed to load (a cold cache while offline). Lets the
	 * validation layer tell "no EU trust data at all" apart from "this certificate simply isn't on
	 * the LOTL", so it warns about a failed LOTL download only when EU trust is genuinely missing.
	 */
	fun euLotlTrustLoaded(): Boolean =
		entries[TrustedSourceId.EuLotl]?.source?.certificates?.isNotEmpty() == true

	/**
	 * Shut the shared executor down. Invoked when a long-running host stops; a
	 * one-shot CLI relies on the daemon threads instead and need not call this.
	 */
	fun shutdown() {
		if (entries.isNotEmpty()) executor.shutdownNow()
	}

	/** Acquire the shared EU LOTL entry, building + offline-first warming once. */
	private fun acquireLotl(): RetainedTl = acquire(TrustedSourceId.EuLotl, displayName = null) { source, onlineLoader ->
		newJob(source, onlineLoader, countingExecutor).apply {
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
		acquire(
			TrustedSourceId.CustomList(config.source, config.signingCertPath),
			displayName = config.name.ifBlank { config.source },
		) { source, onlineLoader ->
			newJob(source, onlineLoader, countingExecutor).apply {
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
	 *
	 * The one-time warm records its outcome — [markRefreshed] on the signal when
	 * usable trust was loaded, or [accumulateFailure] when it was not (a cold cache
	 * while offline) so the session flush notifies the user — keeping the "Last
	 * refreshed" indicator honest instead of silently degrading to an empty trust
	 * store. The entry is still marked [initialized][RetainedTl.initialized] on
	 * failure: re-attempting on every subsequent validation would stall each one up
	 * to the fetch timeout while offline; recovery is via the scheduled cycle or a
	 * manual refresh.
	 */
	private fun acquire(
		id: TrustedSourceId,
		displayName: String?,
		buildJob: (TrustedListsCertificateSource, FileCacheDataLoader) -> TLValidationJob,
	): RetainedTl {
		entries[id]?.let { if (it.initialized) return it }
		val entry = entries.computeIfAbsent(id) { key ->
			val source = TrustedListsCertificateSource()
			val onlineLoader = newOnlineLoader()
			RetainedTl(key, buildJob(source, onlineLoader), source, buildJob, displayName)
		}
		entry.lock.withLock {
			if (!entry.initialized) {
				signal.begin(id)
				val loaded = try {
					refreshOfflineFirst(entry)
				} finally {
					signal.end(id)
				}
				entry.initialized = true
				if (loaded) signal.markRefreshed(Clock.System.now())
				else accumulateFailure(entry.displayName)
			}
		}
		return entry
	}

	/**
	 * Build an online [FileCacheDataLoader] over the persistent cache directory.
	 *
	 * The underlying [CommonsDataLoader] is given the augmented TL-transport trust
	 * ([DssServiceFactory.configureTlTransportTrust]) so member-state lists whose
	 * TLS chains anchor in national eIDAS roots absent from a trimmed `cacerts`
	 * (JetBrains Runtime on the desktop) still download; full path validation is
	 * preserved.
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
					DssServiceFactory.configureTlTransportTrust(this)
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
		executorService: ExecutorService,
	): TLValidationJob {
		val cacheDir = DssServiceFactory.tlCacheDir().also { it.mkdirs() }
		return TLValidationJob().apply {
			setExecutorService(executorService)
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
	 *
	 * @return `true` when the source ended up with usable trust (a cache hit or a
	 *   successful online fetch); `false` when both paths yielded nothing — e.g. a
	 *   cold cache while offline — leaving the source empty.
	 */
	private fun refreshOfflineFirst(entry: RetainedTl): Boolean {
		runCatching { entry.job.offlineRefresh() }
			.onFailure { logger.debug(it) { "Offline TL refresh failed; trying online" } }
		if (entry.source.certificates.isEmpty()) {
			runCatching { entry.job.onlineRefresh() }
				.onFailure { logger.warn(it) { "Online TL refresh failed; trust may be incomplete" } }
		}
		entry.warnings = DssServiceFactory.collectTlWarnings(entry.job.summary)
		return entry.source.certificates.isNotEmpty()
	}

	/**
	 * Online-refresh [job] (the retained job for the scheduled cycle, or a fresh
	 * job for a forced hard refresh), then refresh [entry]'s warnings from it.
	 *
	 * @return `true` when the online fetch completed and left the source with
	 *   usable trust; `false` when the download threw — the last good trust is
	 *   still served — or completed but yielded no certificates.
	 */
	private fun refreshOnline(entry: RetainedTl, job: TLValidationJob): Boolean {
		var fetched = false
		val elapsed = kotlin.system.measureTimeMillis {
			runCatching { job.onlineRefresh() }
				.onSuccess { fetched = true }
				.onFailure { logger.warn(it) { "Online TL refresh of ${entry.id} failed; serving last good trust" } }
		}
		logger.info { "onlineRefresh(${entry.id}) took ${elapsed}ms" }
		entry.warnings = DssServiceFactory.collectTlWarnings(job.summary)
		return fetched && entry.source.certificates.isNotEmpty()
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
