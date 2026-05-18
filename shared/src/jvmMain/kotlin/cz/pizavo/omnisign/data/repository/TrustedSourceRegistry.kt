package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.tsl.source.TLSource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Process-wide registry of retained, identity-keyed DSS trusted-list sources.
 *
 * Replaces the previous single time-keyed `cachedTlSource` with one retained
 * [TLValidationJob] per distinct trust-source identity:
 *
 * - **EU LOTL** — a single shared entry. Its inputs are constant by construction
 *   ([DssServiceFactory.EU_LOTL_URL], the bundled OJ keystore, pivot support), so
 *   every profile that resolves `useEuLotl = true` shares the same parsed source.
 *   This is the ~28-member-state parse + XML-DSig long pole, paid once.
 * - **Custom TLs** — one entry per distinct `(url, signingCertPath)` pair, shared
 *   across every profile that references the same list.
 *
 * Retaining the [TLValidationJob] instance is the core optimization: DSS keys its
 * parsing/validation cache by URL inside the job's `cacheAccessFactory`, so a
 * subsequent `refresh()` skips re-parsing and re-verifying any trusted list whose
 * downloaded bytes are unchanged. A fresh job (the old behavior) starts with an
 * empty cache and re-does that work every time.
 *
 * Per-verifier composition selects only the sources a given [ResolvedConfig]
 * requires and unions them via [CommonCertificateVerifier.setTrustedCertSources],
 * which preserves each source's trust-service metadata (eIDAS qualification) and
 * keeps profiles isolated from each other's custom lists.
 *
 * Atomicity is provided by DSS itself: the synchronizer builds the new trust map
 * locally and swaps it into the stable source in a single call, and leaves the
 * source untouched entirely when nothing changed — so a validation reading the
 * source concurrently with a background refresh never sees a half-built trust set.
 * Each retained source is therefore bound once and never swapped.
 */
class TrustedSourceRegistry {

	/**
	 * Identity of a retained custom trusted-list entry. Two configurations that
	 * resolve to the same URL and signing-certificate path share one parsed source.
	 */
	private data class CustomTlKey(val url: String, val signingCertPath: String?)

	/**
	 * A retained trusted-list source: the long-lived [TLValidationJob] (whose cache
	 * makes re-refreshes cheap), the stable [TrustedListsCertificateSource] it
	 * populates, and the most recent loading warnings.
	 */
	private class RetainedTl(
		val job: TLValidationJob,
		val source: TrustedListsCertificateSource,
	) {
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

	private val lock = ReentrantLock()

	@Volatile
	private var lotl: RetainedTl? = null

	private val customs = ConcurrentHashMap<CustomTlKey, RetainedTl>()

	/**
	 * Shared, bounded, daemon-threaded executor for every retained job.
	 *
	 * DSS gives each [TLValidationJob] its own `Executors.newCachedThreadPool()`;
	 * a single bounded pool instead caps the peak thread/CPU spike during the
	 * unavoidable changed-content refresh. Daemon threads so a one-shot CLI process
	 * is never held open by an idle pool. Lazily created so tests that build no job
	 * (and the no-TL config paths) never allocate it.
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
	 * Mirrors the previous `wireTrustedSources` contract: when no trusted lists and
	 * no direct certs are configured nothing is wired; when only direct certs are
	 * configured only those are wired (no TL warnings).
	 *
	 * @return The union of loading warnings for the sources this config actually
	 *   uses, so a profile is never shown warnings about another profile's lists.
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
	 * Online-refresh every retained source as one coherent set on the global
	 * refresh cycle. Cheap when upstream content is unchanged because each retained
	 * job skips re-parsing/re-verifying unchanged trusted lists.
	 */
	fun refreshAll() {
		lock.withLock {
			lotl?.let { refreshOnline(it) }
			customs.values.forEach { refreshOnline(it) }
		}
	}

	/**
	 * Shut the shared executor down. Invoked when a long-running host stops; a
	 * one-shot CLI relies on the daemon threads instead and need not call this.
	 */
	fun shutdown() {
		if (lotl != null || customs.isNotEmpty()) executor.shutdownNow()
	}

	/**
	 * Get the shared EU LOTL entry, building and offline-first warming it on the
	 * first request under [lock] so concurrent cold callers cannot each launch a
	 * full LOTL refresh (the server thundering-herd case).
	 */
	private fun acquireLotl(): RetainedTl {
		lotl?.let { return it }
		lock.withLock {
			lotl?.let { return it }
			val source = TrustedListsCertificateSource()
			val job = newJob(source).apply {
				setListOfTrustedListSources(
					LOTLSource().apply {
						url = DssServiceFactory.EU_LOTL_URL
						certificateSource = DssServiceFactory.buildOjCertificateSource()
						isPivotSupport = true
					}
				)
			}
			val entry = RetainedTl(job, source)
			refreshOfflineFirst(entry)
			lotl = entry
			return entry
		}
	}

	/**
	 * Get the retained entry for a custom trusted list, building and offline-first
	 * warming it once per distinct identity under [lock].
	 */
	private fun acquireCustom(config: CustomTrustedListConfig): RetainedTl {
		val key = CustomTlKey(config.source, config.signingCertPath)
		customs[key]?.let { return it }
		lock.withLock {
			customs[key]?.let { return it }
			val source = TrustedListsCertificateSource()
			val job = newJob(source).apply {
				setTrustedListSources(
					TLSource().apply {
						url = config.source
						config.signingCertPath?.let {
							certificateSource = DssServiceFactory.buildCertSourceFromFile(it)
						}
					}
				)
			}
			val entry = RetainedTl(job, source)
			refreshOfflineFirst(entry)
			customs[key] = entry
			return entry
		}
	}

	/**
	 * Build a retained [TLValidationJob] bound to [source] with the shared
	 * executor and persistent offline/online file caches.
	 */
	private fun newJob(source: TrustedListsCertificateSource): TLValidationJob {
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
			setOnlineDataLoader(
				FileCacheDataLoader().apply {
					setCacheExpirationTime(cacheExpirationMillis)
					setDataLoader(
						CommonsDataLoader().apply {
							timeoutConnection = DssServiceFactory.TL_FETCH_TIMEOUT_MS
							timeoutSocket = DssServiceFactory.TL_FETCH_TIMEOUT_MS
						}
					)
					setFileCacheDirectory(cacheDir)
				}
			)
		}
	}

	/**
	 * Offline-first warm: serve instantly from the on-disk cache (no network, no
	 * unreachable-host stalls); fall back to a synchronous online refresh only when
	 * the cache is genuinely cold (first ever run). The scheduled [refreshAll]
	 * keeps the retained source fresh afterwards without blocking validations.
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
	 * Online-refresh a single retained entry and refresh its warnings.
	 */
	private fun refreshOnline(entry: RetainedTl) {
		runCatching { entry.job.onlineRefresh() }
			.onFailure { logger.warn(it) { "Scheduled online TL refresh failed; serving last good trust" } }
		entry.warnings = DssServiceFactory.collectTlWarnings(entry.job.summary)
	}
}
