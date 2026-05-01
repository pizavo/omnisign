package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Orchestrates background PKCS#11 library warmup at application startup.
 *
 * Runs the two-phase discovery–initialization cycle on a background coroutine:
 * 1. **Candidate enumeration** — [Pkcs11Discoverer.collectCandidates] gathers all
 *    discoverable PKCS#11 libraries from OS-native sources, the app-data drop directory,
 *    and user-supplied paths.
 * 2. **Bounded-parallel in-process registration** — candidates are probed via subprocess
 *    with at most [maxParallelism] running concurrently (default `2`) so that weak
 *    hardware does not thrash under JVM cold-start contention.  Libraries whose subprocess
 *    exits successfully are loaded in-process via [Pkcs11SessionManager.registerSafe],
 *    establishing a persistent `C_Initialize` session.  Crashed libraries are permanently
 *    blacklisted via [Pkcs11SessionManager.registerCrashed]; **timed-out** libraries are
 *    *not* blacklisted — discovery falls back to subprocess on demand instead, so a
 *    transient hang during warmup does not permanently disable a healthy library.
 *
 * Once warmup completes, further token probes in [Pkcs11Discoverer.probeLibrary] use the
 * fast in-process path (`C_GetSlotList` + `C_GetTokenInfo`, milliseconds) for libraries
 * that registered successfully and fall back to subprocess for everything else.  Discovery
 * never blocks on warmup — it can run concurrently and just yields slower (subprocess-only)
 * results for libraries that have not yet been registered.
 *
 * @property discoverer The discoverer used to list candidate library paths.
 * @property sessionManager The session manager that holds persistent in-process handles.
 * @property warmupSignal Shared mutable flow that this service writes `true` to upon
 *   completion.  Exposed as [warmedUp] for UI consumption (e.g. a "warming up…" indicator);
 *   not used as a discovery gate.
 * @property probeTimeoutSeconds Timeout for each subprocess probe during warmup.
 * @property maxParallelism Upper bound on concurrent warmup probes.  Each probe spawns a
 *   fresh JVM subprocess; running too many in parallel on weak hardware causes cold-start
 *   contention and false timeouts.  Defaults to `2`, which keeps throughput reasonable on
 *   strong machines without thrashing slow ones.
 */
class Pkcs11WarmupService(
	private val discoverer: Pkcs11Discoverer,
	private val sessionManager: Pkcs11SessionManager,
	private val warmupSignal: MutableStateFlow<Boolean>,
	private val probeTimeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
	private val maxParallelism: Int = DEFAULT_MAX_PARALLELISM,
) {

	/**
	 * Whether the warmup cycle has completed.
	 *
	 * Consumers (e.g., ViewModels) can collect this flow to show a progress indicator
	 * during the initial library discovery phase.  Discovery does **not** block on it —
	 * this signal is purely informational for the UI.
	 */
	val warmedUp: StateFlow<Boolean> = warmupSignal.asStateFlow()

	/**
	 * Run the bounded-parallel warmup for all discoverable PKCS#11 libraries.
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

			logger.info { "Warming up ${candidates.size} PKCS#11 candidate library(-ies): ${candidates.map { it.first }}" }

			val gate = Semaphore(maxParallelism)
			coroutineScope {
				candidates.map { (name, path) ->
					async(Dispatchers.IO) {
						gate.withPermit { warmupSingleLibrary(name, path) }
					}
				}.awaitAll()
			}

			val elapsed = System.currentTimeMillis() - startTime
			val sessionCount = candidates.count { (_, path) -> sessionManager.hasSession(path) }
			val crashedCount = candidates.count { (_, path) -> sessionManager.isCrashed(path) }
			logger.info {
				"PKCS#11 warmup complete in ${elapsed}ms — " +
						"$sessionCount/${candidates.size} sessions established, " +
						"$crashedCount crashed (timed-out libs are not counted; they retry on demand)"
			}
		} finally {
			warmupSignal.value = true
		}
	}

	/**
	 * Probe a single library via subprocess and register the result in [sessionManager].
	 *
	 * The subprocess is spawned via [resolveProbeCommand] and monitored for completion
	 * within [probeTimeoutSeconds].  Exit codes are analyzed to distinguish clean exits,
	 * crashes (SIGSEGV / SIGABRT), and timeouts:
	 *
	 * - **Exit 0** → the library is safe; register in-process via [Pkcs11SessionManager.registerSafe].
	 * - **Non-zero exit (crash)** → the library crashes during `C_Initialize`; permanently
	 *   blacklist via [Pkcs11SessionManager.registerCrashed] so it is never loaded in-process.
	 * - **Timeout (hang)** → the subprocess hung; forcibly kill it but **do not** blacklist —
	 *   discovery will subprocess-probe on demand.  A transient timeout (slow USB enumeration,
	 *   thrash on a weak box) should not disable a healthy library for the rest of the session.
	 *
	 * @param name Human-readable library display name (for logging).
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	private fun warmupSingleLibrary(name: String, libraryPath: String) {
		logger.debug { "Warmup probing '$name' at '$libraryPath'" }

		try {
			when (val result = runProbeSubprocess(libraryPath, probeTimeoutSeconds)) {
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
					sessionManager.registerCrashed(libraryPath)
				}

				is Pkcs11SubprocessResult.Success -> {
					logger.debug {
						"Warmup subprocess pid=${result.pid} for '$name' ('$libraryPath') succeeded — registering in-process"
					}
					sessionManager.registerSafe(libraryPath)

					if (sessionManager.hasSession(libraryPath)) {
						logger.info { "Warmup complete for '$name': in-process session established" }
					} else {
						logger.warn { "Warmup for '$name': subprocess passed but in-process registration failed" }
					}
				}
			}
		} catch (e: Exception) {
			logger.warn(e) { "Warmup failed for '$name' ('$libraryPath') — marking as crashed" }
			sessionManager.registerCrashed(libraryPath)
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
