package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
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
import eu.europa.esig.dss.tsl.sync.ExpirationAndSignatureCheckStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
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
 * For the same reason, a job that leaves its source without certificates is not
 * kept, and the job of a hard refresh replaces the retained one (see
 * [retainUnlessEmpty] and [forceRefreshAll]).
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
 *
 * A list supplies trust only while its signature verifies against the certificates that vouch
 * for it (see [RetainedTl.synchronizationStrategy]). One that does not, for instance because it
 * was changed after it was signed, is left out and named in the warnings of the operations that
 * use its source.
 *
 * @param signal Where acquisitions and refreshes are reported.
 * @param cacheDirectory The directory downloaded lists are cached in, [DssServiceFactory.tlCacheDir]
 *   unless a test needs a cache of its own.
 */
class TrustedSourceRegistry(
	private val signal: TrustedListRefreshSignal = TrustedListRefreshSignal(),
	private val cacheDirectory: () -> File = { DssServiceFactory.tlCacheDir() },
) {

	/**
	 * A retained trusted-list source: the stable [TrustedListsCertificateSource] its jobs
	 * populate, its own lock, the most recent loading warnings, [buildJob] — the factory that
	 * builds a job over the source, both the long-lived retained [job] (whose cache makes
	 * scheduled re-refreshes cheap) and the *fresh* job (empty cache) of a user-initiated hard
	 * refresh, which must genuinely re-download and re-parse and is retained after it —
	 * [displayName], the source's human-readable name for user-facing failure notifications
	 * (`null` for the EU LOTL), and [signingCertPath], the file holding a custom list's signing
	 * certificate (`null` for the EU LOTL, whose signing certificates are bundled, and for a
	 * custom list configured without one).
	 *
	 * The registry reads [signingCertPath] itself and hands the certificate to [buildJob] (see
	 * [buildJobFor]), so [job] stays `null` while the file cannot be read, and
	 * [signingCertificateFailure] holds the reason it last could not, so that reason is logged
	 * once rather than on every attempt. [unverifiedWarning] does the same for the warning about
	 * lists whose signature could not be verified (see [recordWarnings]).
	 */
	private class RetainedTl(
		val id: TrustedSourceId,
		val source: TrustedListsCertificateSource,
		val buildJob: (TrustedListsCertificateSource, FileCacheDataLoader, CertificateSource?) -> TLValidationJob,
		val displayName: String?,
		val signingCertPath: String?,
	) {
		val lock = ReentrantLock()

		/**
		 * Decides which of this source's lists supply trust, for every job built for it.
		 *
		 * A list whose signature could not be verified is left out: the EU LOTL, verified against
		 * the bundled Official Journal certificates, the member-state lists it points to, verified
		 * against the certificates it gives for each, and a custom list configured with a signing
		 * certificate. Such a list supplies no trust even when an earlier version of it did. A
		 * custom list configured without a signing certificate cannot be verified, so it is used
		 * whatever its signature. Expired lists are used either way, so that a list its operator is
		 * late to reissue still vouches for the services it names.
		 */
		val synchronizationStrategy = ExpirationAndSignatureCheckStrategy().apply {
			setAcceptInvalidTrustedList(id is TrustedSourceId.CustomList && signingCertPath == null)
			setAcceptInvalidListOfTrustedLists(false)
			setAcceptExpiredTrustedList(true)
			setAcceptExpiredListOfTrustedLists(true)
		}

		@Volatile
		var job: TLValidationJob? = null

		@Volatile
		var initialized = false

		@Volatile
		var warnings: List<LocalizableText> = emptyList()

		@Volatile
		var signingCertificateFailure: String? = null

		@Volatile
		var unverifiedWarning: LocalizableText? = null
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
	 * A source that could not be loaded, such as a custom list whose signing certificate cannot
	 * be read, is wired in empty and adds the warning that says why, so the operation goes on
	 * with the other sources.
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
	): List<LocalizableText> = withProgressSession {
		val validation = config.validation
		val sources = mutableListOf<CertificateSource>()
		val warnings = mutableListOf<LocalizableText>()

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
	 * Used by the startup warmup so the first validation hits warm sources, and
	 * before every manual hard refresh so that refresh covers every configured list.
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
	 * throws is accumulated (its last good trust keeps being served), and so is each
	 * source left without trust, such as a custom list whose new version cannot be
	 * verified; they are flushed as one aggregate failure when the session ends, and a
	 * fresh "Last refreshed" timestamp is stamped when at least one source refreshed —
	 * both when the cycle is mixed.
	 *
	 * A source that has no retained job, because its signing certificate could not be
	 * read or its last job left it without certificates (see [retainUnlessEmpty]), gets
	 * one built first, so a restored or corrected certificate file is picked up by the
	 * next cycle; while the file still cannot be read, the source is accumulated as failed.
	 */
	fun refreshAll() = withProgressSession {
		var loadedAny = false
		entries.values.forEach { entry ->
			entry.lock.withLock {
				signal.begin(entry.id)
				try {
					val job = retainedJob(entry)
					if (job != null && refreshOnline(entry, job)) loadedAny = true
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
	 * heavy by design; that is what the user asked for.
	 *
	 * The fresh job then becomes the retained one, with a cache-gated loader again,
	 * because the source now holds what it loaded. The old job never saw the copies
	 * this refresh downloaded, and DSS updates the source only when a job parses a
	 * list again, so under the old job a list this refresh could not download or
	 * verify would stay out of the source even after its URL served the copy the
	 * old job had loaded, until its operator published a new version. Like any
	 * other, the fresh job is not kept when it left the source without
	 * certificates (see [retainUnlessEmpty]).
	 *
	 * The outcome is recorded on the signal as [refreshAll] records it, and also
	 * returned, because the session flush may merge it with the failures of an
	 * operation running at the same time. A source whose signing certificate cannot
	 * be read gets no fresh job and is among the failed ones; the others are still
	 * refreshed. A source that loaded earlier keeps serving its last good trust when
	 * its download fails, but not when the list it downloads cannot be verified.
	 *
	 * @return The sources this refresh could not load, or `null` when every one
	 *   of them loaded or none is retained.
	 */
	fun forceRefreshAll(): TrustedListRefreshFailure? = withProgressSession {
		logger.info { "Hard refresh: ${entries.size} retained trusted source(s) to re-download" }
		var loadedAny = false
		val failed = mutableListOf<RetainedTl>()
		entries.values.forEach { entry ->
			entry.lock.withLock {
				signal.begin(entry.id)
				try {
					logger.info { "Hard-refreshing ${entry.id} (fresh job, forced network re-download)" }
					val freshJob = buildJobFor(entry) {
						newOnlineLoader().apply { setCacheExpirationTime(FORCE_REFRESH_EXPIRATION_MS) }
					}
					val loaded = freshJob != null && refreshOnline(entry, freshJob)
					freshJob?.setOnlineDataLoader(newOnlineLoader())
					if (loaded) {
						loadedAny = true
					} else {
						accumulateFailure(entry.displayName)
						failed += entry
					}
				} finally {
					signal.end(entry.id)
				}
			}
		}
		val now = Clock.System.now()
		if (loadedAny) signal.markRefreshed(now)
		TrustedListRefreshFailure.of(
			lotlFailed = failed.any { it.displayName == null },
			failedCustomNames = failed.mapNotNull { it.displayName },
			at = now,
		)
	}

	/**
	 * Whether any retained trusted source finished loading but holds no certificates —
	 * i.e. it failed to obtain trust (a cold cache while offline, an unreachable
	 * source URL, a signing certificate that cannot be read, or a list whose signature
	 * could not be verified). The refresh scheduler
	 * polls this to retry soon after a failure instead of waiting a full cycle.
	 * `false` when every retained source has trust, or none is retained.
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
	private fun acquireLotl(): RetainedTl =
		acquire(TrustedSourceId.EuLotl, displayName = null, signingCertPath = null) { source, onlineLoader, _ ->
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
			signingCertPath = config.signingCertPath,
		) { source, onlineLoader, signingCertificate ->
			newJob(source, onlineLoader, countingExecutor).apply {
				setTrustedListSources(
					TLSource().apply {
						url = config.source
						signingCertificate?.let { certificateSource = it }
					}
				)
			}
		}

	/**
	 * Get-or-create the retained entry for [id]. The cheap shell (the source, with no
	 * job and no I/O) is created atomically per id; building the job and the slow
	 * offline-first warm run once under the entry's own lock, bracketed with the
	 * refresh signal so the first lazy load is visible to scoped waiters.
	 *
	 * The one-time warm records its outcome —
	 * [markRefreshed][TrustedListRefreshSignal.markRefreshed] on the signal when usable
	 * trust was loaded, or [accumulateFailure] when it was not (a cold cache while
	 * offline, a signing certificate that cannot be read, or a list whose signature
	 * could not be verified) so the session flush
	 * notifies the user — keeping the "Last refreshed" indicator honest instead of
	 * silently degrading to an empty trust store. The entry is still marked
	 * [initialized][RetainedTl.initialized] on failure: re-attempting on every
	 * subsequent validation would stall each one up to the fetch timeout while
	 * offline; recovery is via the scheduled cycle or a manual refresh.
	 */
	private fun acquire(
		id: TrustedSourceId,
		displayName: String?,
		signingCertPath: String?,
		buildJob: (TrustedListsCertificateSource, FileCacheDataLoader, CertificateSource?) -> TLValidationJob,
	): RetainedTl {
		entries[id]?.let { if (it.initialized) return it }
		val entry = entries.computeIfAbsent(id) { key ->
			RetainedTl(key, TrustedListsCertificateSource(), buildJob, displayName, signingCertPath)
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
	 * The retained job of [entry], built over a cache-gated online loader on first use and
	 * again after a refresh dropped it (see [retainUnlessEmpty]). `null` while the entry's
	 * signing certificate cannot be read; each call tries again, so a restored file is picked
	 * up by the next refresh.
	 */
	private fun retainedJob(entry: RetainedTl): TLValidationJob? =
		entry.job ?: buildJobFor(entry, ::newOnlineLoader)?.also { entry.job = it }

	/**
	 * Build a job for [entry] over the loader [onlineLoader] creates, after reading the
	 * entry's signing certificate from [RetainedTl.signingCertPath]. The job leaves out a list
	 * whose signature could not be verified, as [RetainedTl.synchronizationStrategy] decides.
	 *
	 * No job is built when the certificate cannot be read — the file is missing or
	 * unreadable, or holds no certificate — because a list must never be loaded without
	 * the certificate configured to verify its signature; [recordUnreadableSigningCertificate]
	 * says why instead. [onlineLoader] is only called once the certificate has been read, so a
	 * list that cannot be loaded does not touch the cache directory.
	 *
	 * @return The new job, or `null` when the signing certificate cannot be read.
	 */
	private fun buildJobFor(entry: RetainedTl, onlineLoader: () -> FileCacheDataLoader): TLValidationJob? {
		val signingCertificate = entry.signingCertPath?.let { path ->
			try {
				DssServiceFactory.buildCertSourceFromFile(path)
			} catch (e: Exception) {
				recordUnreadableSigningCertificate(entry, path, e)
				return null
			}
		}
		entry.signingCertificateFailure = null
		return entry.buildJob(entry.source, onlineLoader(), signingCertificate).apply {
			setSynchronizationStrategy(entry.synchronizationStrategy)
		}
	}

	/**
	 * Record that the signing certificate of [entry] at [path] cannot be read, for the reason
	 * [cause] gives. Only custom lists have a [RetainedTl.signingCertPath], and each of them
	 * has a [RetainedTl.displayName].
	 *
	 * The reason is logged in one line, at WARN the first time and at DEBUG while it stays the
	 * same, so neither the warm-up and hard refresh of one `config tl refresh` nor the
	 * scheduler's retries repeat it. An entry without a retained job holds no trust from the
	 * list, so its warnings become
	 * [MessageKey.WARNING_TRUSTED_LIST_SIGNING_CERTIFICATE_UNREADABLE], which reaches the
	 * report of every validation that uses the list. An entry with a retained job keeps
	 * serving the trust it loaded before, and its warnings with it.
	 */
	private fun recordUnreadableSigningCertificate(entry: RetainedTl, path: String, cause: Exception) {
		val reason = cause.toString()
		val message = "Trusted list '${entry.displayName}' is not loaded because its signing certificate " +
				"$path cannot be read: $reason"
		if (reason == entry.signingCertificateFailure) logger.debug { message } else logger.warn { message }
		entry.signingCertificateFailure = reason
		if (entry.job == null) {
			entry.warnings = listOf(
				LocalizableText.of(
					MessageKey.WARNING_TRUSTED_LIST_SIGNING_CERTIFICATE_UNREADABLE,
					entry.displayName.orEmpty(),
					path,
				)
			)
		}
	}

	/**
	 * Build an online [FileCacheDataLoader] over the persistent [cacheDirectory].
	 *
	 * The underlying [CommonsDataLoader] is given the augmented TL-transport trust
	 * ([DssServiceFactory.configureTlTransportTrust]) so member-state lists whose
	 * TLS chains anchor in national eIDAS roots absent from a trimmed `cacerts`
	 * (JetBrains Runtime on the desktop) still download; full path validation is
	 * preserved.
	 *
	 * Used for the retained job's cache-gated loader, and, with expiration forced
	 * to zero, for the loader a [forceRefreshAll] hard refresh downloads with
	 * before its job is retained.
	 */
	private fun newOnlineLoader(): FileCacheDataLoader {
		val cacheDir = cacheDirectory().also { it.mkdirs() }
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
	 * executor, a never-expiring offline cache over [cacheDirectory], and the supplied
	 * [onlineLoader].
	 */
	private fun newJob(
		source: TrustedListsCertificateSource,
		onlineLoader: FileCacheDataLoader,
		executorService: ExecutorService,
	): TLValidationJob {
		val cacheDir = cacheDirectory().also { it.mkdirs() }
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
	 * [refreshAll] keeps the retained source fresh afterwards. The job is kept
	 * for it unless it left the source without certificates (see [retainUnlessEmpty]).
	 *
	 * @return `true` when the source ended up with usable trust (a cache hit or a
	 *   successful online fetch); `false` when both paths yielded nothing — e.g. a
	 *   cold cache while offline — or no job could be built because the signing
	 *   certificate cannot be read, leaving the source empty.
	 */
	private fun refreshOfflineFirst(entry: RetainedTl): Boolean {
		val job = retainedJob(entry) ?: return false
		runCatching { job.offlineRefresh() }
			.onFailure { logger.debug(it) { "Offline TL refresh failed; trying online" } }
		if (entry.source.certificates.isEmpty()) {
			runCatching { job.onlineRefresh() }
				.onFailure { logger.warn(it) { "Online TL refresh failed; trust may be incomplete" } }
		}
		recordWarnings(entry, job)
		retainUnlessEmpty(entry, job)
		return entry.source.certificates.isNotEmpty()
	}

	/**
	 * Online-refresh [job] (the retained job for the scheduled cycle, or a fresh
	 * job for a forced hard refresh), then refresh [entry]'s warnings from it and
	 * keep it as the entry's retained job unless it left the source without
	 * certificates (see [retainUnlessEmpty]).
	 *
	 * @return `true` when the online fetch completed and left the source with
	 *   usable trust; `false` when the download threw — the last good trust is
	 *   still served — or completed but yielded no certificates, for instance because
	 *   the list it downloaded could not be verified.
	 */
	private fun refreshOnline(entry: RetainedTl, job: TLValidationJob): Boolean {
		var fetched = false
		val elapsed = kotlin.system.measureTimeMillis {
			runCatching { job.onlineRefresh() }
				.onSuccess { fetched = true }
				.onFailure { logger.warn(it) { "Online TL refresh of ${entry.id} failed; serving last good trust" } }
		}
		logger.info { "onlineRefresh(${entry.id}) took ${elapsed}ms" }
		recordWarnings(entry, job)
		retainUnlessEmpty(entry, job)
		return fetched && entry.source.certificates.isNotEmpty()
	}

	/**
	 * Keep [job] as the retained job of [entry], unless the refresh it just ran left the source
	 * without certificates. Such a job is dropped, so the next refresh builds a new one, which
	 * parses and verifies every list again.
	 *
	 * A job gives the source new certificates only when it parses a list again, which it does
	 * only when the list's downloaded copy changes. A job that left the source empty would keep
	 * it empty after the cause was gone: once a pivot list that failed to download comes through,
	 * it verifies the EU LOTL again but does not update the source, and it reads a custom list's
	 * signing certificate only when it is built, so it never sees that file corrected. Dropping
	 * such a job loses no trust. A job that left only some lists out is kept, so that it goes on
	 * serving the lists it then fails to download; those are member-state lists, which it verifies
	 * again whenever they or the EU LOTL change.
	 */
	private fun retainUnlessEmpty(entry: RetainedTl, job: TLValidationJob) {
		entry.job = job.takeIf { entry.source.certificates.isNotEmpty() }
	}

	/**
	 * Replace the warnings of [entry] with those of [job]'s last refresh.
	 *
	 * A warning about lists left out because their signature could not be verified is logged
	 * too, so that `config tl refresh`, which reports such a list only as not refreshed, says
	 * why. Like [recordUnreadableSigningCertificate], it is logged at WARN when it is new and at
	 * DEBUG while it stays the same.
	 */
	private fun recordWarnings(entry: RetainedTl, job: TLValidationJob) {
		val warnings = DssServiceFactory.collectTlWarnings(job.summary, entry.synchronizationStrategy)
		val unverified = warnings.firstOrNull {
			(it as? LocalizableText.Keyed)?.key == MessageKey.WARNING_TRUSTED_LIST_SIGNATURE_UNVERIFIED
		}
		if (unverified != null) {
			if (unverified == entry.unverifiedWarning) logger.debug { unverified.english() }
			else logger.warn { unverified.english() }
		}
		entry.unverifiedWarning = unverified
		entry.warnings = warnings
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
