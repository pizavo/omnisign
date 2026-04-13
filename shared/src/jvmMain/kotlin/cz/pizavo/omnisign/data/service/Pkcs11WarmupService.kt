package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates background PKCS#11 library warmup at application startup.
 *
 * Runs the two-phase discovery–initialization cycle on a background coroutine:
 * 1. **Candidate enumeration** — [Pkcs11Discoverer.collectCandidates] gathers all
 *    discoverable PKCS#11 libraries from OS-native sources, the curated fallback
 *    list, the app-data drop directory, and user-supplied paths.
 * 2. **Parallel in-process registration** — each candidate is probed via a subprocess
 *    to safely detect crashes and hangs.  Libraries whose subprocess exits successfully
 *    are then loaded in-process via [Pkcs11SessionManager.registerSafe], establishing
 *    a persistent `C_Initialize` session.  Libraries whose subprocess crashes or times
 *    out are recorded via [Pkcs11SessionManager.registerCrashed].
 *
 * Once warmup completes, further token probes in [Pkcs11Discoverer.probeLibrary]
 * use the fast in-process path (`C_GetSlotList` + `C_GetTokenInfo`, milliseconds)
 * instead of spawning a new subprocess (~18 s for some middleware).
 *
 * @property discoverer The discoverer used to list candidate library paths.
 * @property sessionManager The session manager that holds persistent in-process handles.
 * @property warmupSignal Shared mutable flow that this service writes `true` to upon
 *   completion.  The same flow is injected into [Pkcs11Discoverer] as its
 *   `warmupReady` parameter so that [Pkcs11Discoverer.discoverTokens] suspends
 *   until warmup finishes.  Injected via Koin to break the circular dependency
 *   between [Pkcs11Discoverer] and this service.
 * @property probeTimeoutSeconds Timeout for each subprocess probe during warmup.
 */
class Pkcs11WarmupService(
	private val discoverer: Pkcs11Discoverer,
	private val sessionManager: Pkcs11SessionManager,
	private val warmupSignal: MutableStateFlow<Boolean>,
	private val probeTimeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
) {

	/**
	 * Whether the warmup cycle has completed.
	 *
	 * Consumers (e.g., ViewModels) can collect this flow to show a progress indicator
	 * during the initial library discovery phase.  Also observed by
	 * [Pkcs11Discoverer.discoverTokens] (via the shared [warmupSignal]) to gate
	 * discovery until in-process sessions are established.
	 */
	val warmedUp: StateFlow<Boolean> = warmupSignal.asStateFlow()

	/**
	 * Run the two-phase warmup for all discoverable PKCS#11 libraries.
	 *
	 * Candidate libraries are enumerated from OS-native sources, the curated fallback
	 * list, the app-data drop directory, and user-supplied paths.  Each candidate is
	 * probed in parallel via [Dispatchers.IO].
	 *
	 * This method is safe to call multiple times — further calls after the first
	 * successful warmup return immediately.
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
			logger.info { "Starting PKCS#11 background warmup (timeout=${probeTimeoutSeconds}s)" }
			val startTime = System.currentTimeMillis()

			val candidates = discoverer.collectCandidates(appDataPkcs11Dir, userPkcs11Libraries)

			if (candidates.isEmpty()) {
				logger.info { "No PKCS#11 candidate libraries found — warmup complete" }
				return
			}

			logger.info { "Warming up ${candidates.size} PKCS#11 candidate library(-ies): ${candidates.map { it.first }}" }

			coroutineScope {
				candidates.map { (name, path) ->
					async(Dispatchers.IO) {
						warmupSingleLibrary(name, path)
					}
				}.forEach { it.await() }
			}

			val elapsed = System.currentTimeMillis() - startTime
			val sessionCount = candidates.count { (_, path) -> sessionManager.hasSession(path) }
			val crashedCount = candidates.count { (_, path) -> sessionManager.isCrashed(path) }
			logger.info {
				"PKCS#11 warmup complete in ${elapsed}ms — " +
						"$sessionCount/${candidates.size} sessions established, " +
						"$crashedCount crashed/timed out"
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
	 * - **Non-zero exit** → the library crashed; mark via [Pkcs11SessionManager.registerCrashed].
	 * - **Timeout** → the subprocess hung; forcibly kill and mark as crashed.
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
								"after ${probeTimeoutSeconds}s — marking as crashed"
					}
					sessionManager.registerCrashed(libraryPath)
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
	}
}

