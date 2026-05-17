package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Validates discovered PKCS#11 libraries against a crash blacklist at application startup.
 *
 * Earlier revisions also pre-loaded each safe library in-process via JNA so subsequent slot
 * scans could skip subprocess overhead.  That created a second PKCS#11 consumer alongside
 * SunPKCS11 (which DSS uses for signing) and was a recurring source of subtle interaction
 * bugs.  The current model is **validate-only**: each candidate is probed via subprocess;
 * crashed libraries are recorded in [Pkcs11CrashBlacklist] so [Pkcs11ProbeCache.probeLibrary]
 * skips them on subsequent calls.  All actual token probing now runs out-of-process.
 *
 * Probes run with at most [maxParallelism] concurrently (default `2`) so weak hardware does
 * not thrash on N parallel JVM cold-starts.  Crashes (non-zero exit) are recorded in
 * [Pkcs11CrashBlacklist], which suppresses a library only after it crashes repeatedly
 * within a sliding window and lets the record decay afterwards; **timeouts** are never
 * recorded — a transient hang during warmup should not disable a healthy library, so
 * discovery falls back to subprocess on demand instead.
 *
 * Discovery never blocks on warmup.  Warmup publishes its in-progress state through
 * [Pkcs11Discoverer.discoveryRunning] by wrapping the validation pass in
 * [Pkcs11Discoverer.beginDiscovery] / [Pkcs11Discoverer.endDiscovery].  This lets passive
 * cache readers (notably [DssTokenService]'s sign-dialog path) suspend on the unified
 * discovery signal without having to know which producer is currently running.
 *
 * @property discoverer The discoverer used to list candidate library paths and to bracket
 *   the validation pass in [Pkcs11Discoverer.beginDiscovery] / [Pkcs11Discoverer.endDiscovery].
 * @property probeCache The shared probe cache primed with each validated library's identities.
 * @property prober Process-isolated probe runner; each candidate is validated by spawning a
 *   probe subprocess through it.
 * @property crashBlacklist The blacklist updated when a subprocess validation crashes.
 * @property warmupSignal Shared mutable flow that this service writes `true` to upon
 *   completion.  Used internally to short-circuit repeated [warmup] invocations once the
 *   first pass has settled.  Callers that want to react to discovery progress should
 *   observe [Pkcs11Discoverer.discoveryRunning] instead, which covers both warmup and
 *   any subsequent invalidator-launched rediscovery cycles.
 * @property probeTimeoutSeconds Timeout for each subprocess probe during warmup.
 * @property maxParallelism Upper bound on concurrent warmup probes.  Each probe spawns a
 *   fresh JVM subprocess; running too many in parallel on weak hardware causes cold-start
 *   contention and false timeouts.  Defaults to `2`.
 */
class Pkcs11WarmupService(
	private val discoverer: Pkcs11Discoverer,
	private val probeCache: Pkcs11ProbeCache,
	private val prober: Pkcs11Prober,
	private val crashBlacklist: Pkcs11CrashBlacklist,
	private val warmupSignal: MutableStateFlow<Boolean>,
	private val probeTimeoutSeconds: Long = Pkcs11Prober.DEFAULT_PROBE_TIMEOUT_SECONDS,
	private val maxParallelism: Int = DEFAULT_MAX_PARALLELISM,
) {

	/**
	 * Run the bounded-parallel validation pass for all discoverable PKCS#11 libraries.
	 *
	 * Candidate libraries are enumerated from OS-native sources, the app-data drop directory,
	 * and user-supplied paths.  At most [maxParallelism] candidates are probed in parallel
	 * via [Dispatchers.IO]; the remainder queue behind a [Semaphore] permit.
	 *
	 * This method is safe to call multiple times — further calls after the first successful
	 * warmup return immediately.
	 *
	 * @param appDataPkcs11Dir Optional drop directory for user-placed PKCS#11 libraries.
	 * @param userPkcs11Libraries Additional `(display name, path)` pairs from config.
	 */
	suspend fun warmup(
		appDataPkcs11Dir: java.io.File? = null,
		userPkcs11Libraries: List<Pair<String, String>> = emptyList(),
	) {
		if (warmupSignal.value) {
			logger.debug { "PKCS#11 warmup already completed — skipping" }
			return
		}

		discoverer.beginDiscovery()
		try {
			logger.info {
				"Starting PKCS#11 background warmup (timeout=${probeTimeoutSeconds}s, " +
						"maxParallelism=$maxParallelism)"
			}
			val startTime = System.currentTimeMillis()

			val candidates = discoverer.collectCandidates(appDataPkcs11Dir, userPkcs11Libraries)

			if (candidates.isEmpty()) {
				logger.info { "No PKCS#11 candidate libraries found — warmup complete" }
				return
			}

			logger.info { "Validating ${candidates.size} PKCS#11 candidate library(-ies): ${candidates.map { it.first }}" }

			val gate = Semaphore(maxParallelism)
			coroutineScope {
				candidates.map { (name, path) ->
					async(Dispatchers.IO) {
						gate.withPermit { warmupSingleLibrary(name, path) }
					}
				}.awaitAll()
			}

			val elapsed = System.currentTimeMillis() - startTime
			val crashedCount = candidates.count { (_, path) -> crashBlacklist.isCrashed(path) }
			logger.info {
				"PKCS#11 warmup complete in ${elapsed}ms — " +
						"$crashedCount/${candidates.size} library(-ies) crashed and were blacklisted"
			}
		} finally {
			warmupSignal.value = true
			discoverer.endDiscovery()
		}
	}

	/**
	 * Probe a single library via subprocess and update [crashBlacklist] when it crashes.
	 *
	 * The subprocess is spawned via the injected [Pkcs11Prober] and monitored for completion
	 * within [probeTimeoutSeconds].  Exit codes are analyzed to distinguish clean exits,
	 * crashes (SIGSEGV / SIGABRT), and timeouts:
	 *
	 * - **Exit 0** → the library is safe; nothing to record (it stays off the blacklist).
	 * - **Non-zero exit (crash)** → the library crashed during `C_Initialize`; record it via
	 *   [Pkcs11CrashBlacklist.registerCrashed], which suppresses the library only after
	 *   repeated crashes within the window and decays the record afterwards.
	 * - **Timeout (hang)** → the subprocess hung; forcibly kill it but **do not** blacklist —
	 *   discovery will subprocess-probe on demand.
	 *
	 * @param name Human-readable library display name (for logging).
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	private fun warmupSingleLibrary(name: String, libraryPath: String) {
		logger.debug { "Warmup probing '$name' at '$libraryPath'" }

		try {
			when (val result = prober.runProbe(libraryPath, probeTimeoutSeconds)) {
				null -> {
					logger.warn { "Cannot resolve probe command for '$name' ('$libraryPath') — skipping warmup" }
				}

				is Pkcs11SubprocessResult.TimedOut -> {
					logger.warn {
						"Warmup subprocess pid=${result.pid} for '$name' ('$libraryPath') timed out " +
								"after ${probeTimeoutSeconds}s — will retry via subprocess on demand"
					}
				}

				is Pkcs11SubprocessResult.Crashed -> {
					val signal = if (result.exitCode > 128) " (${signalName(result.exitCode - 128)})" else ""
					logger.warn {
						buildString {
							append("Warmup subprocess pid=${result.pid} for '$name' ('$libraryPath') ")
							append("exited with code ${result.exitCode}$signal — marking as crashed")
							if (result.stderr.isNotEmpty()) {
								append("\n  stderr: ${result.stderr}")
							}
						}
					}
					crashBlacklist.registerCrashed(libraryPath)
				}

				is Pkcs11SubprocessResult.Success -> {
					val identities = prober.parseIdentities(result.stdout, libraryPath)
					probeCache.primeCache(libraryPath, identities)
					logger.info {
						"Warmup validated '$name' — library loads cleanly in subprocess " +
								"(${identities.size} identity(-ies) cached)"
					}
				}
			}
		} catch (e: Exception) {
			logger.warn(e) { "Warmup failed for '$name' ('$libraryPath') — marking as crashed" }
			crashBlacklist.registerCrashed(libraryPath)
		}
	}

	private companion object {
		val logger = KotlinLogging.logger {}

		/**
		 * Default ceiling on concurrent warmup probes.  Each probe spawns a fresh JVM
		 * subprocess; capping concurrency at `2` keeps throughput reasonable on strong
		 * hardware while avoiding cold-start contention on weak machines.
		 */
		const val DEFAULT_MAX_PARALLELISM = 2
	}
}
