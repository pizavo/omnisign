package cz.pizavo.omnisign.data.service

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import cz.pizavo.omnisign.data.service.Pkcs11SessionManager.Companion.REGISTER_TIMEOUT_SECONDS
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Manages persistent in-process PKCS#11 library sessions for fast token probing.
 *
 * After a PKCS#11 library has been safely validated via a subprocess probe (crash
 * isolation), this manager loads the library in-process via JNA, calls `C_Initialize`
 * once, and keeps the handle alive for the application's lifetime.  Subsequent token
 * presence checks use lightweight `C_GetSlotList` + `C_GetTokenInfo` calls that
 * complete in milliseconds — the same approach used by Adobe Acrobat.
 *
 * Libraries that crashed or timed out during subprocess probing are recorded in
 * [crashedLibraries] and are never loaded in-process.
 *
 * **Timeout safety**: [registerSafe] runs native calls on a short-lived **daemon thread**
 * guarded by a [CompletableFuture] with a strict timeout (one-time per library, during
 * warmup).  [probeInProcess] submits work to a **single persistent daemon thread**
 * ([probeExecutor]) that is reused across calls.  Reusing one thread is critical because:
 * 1. PKCS#11 middleware (notably SafeNet) uses thread-local storage; a fresh thread triggers
 *    expensive per-thread re-initialization (USB bus scan) on every call.
 * 2. p11-kit-proxy internally delegates to the same native libraries that are probed directly;
 *    concurrent calls from separate threads can deadlock inside the native code when
 *    `C_Initialize` was called with `NULL` (no OS locking).
 *
 * If a probe times out (middleware hung), the stuck executor is abandoned and replaced by
 * a fresh one so subsequent probes are not blocked behind the stuck native call.
 *
 * Thread-safety is guaranteed by [ConcurrentHashMap] for reads and `synchronized`
 * blocks for one-time initialization of each library path.
 */
class Pkcs11SessionManager {

	/**
	 * Persistent JNA handles for successfully initialized PKCS#11 libraries,
	 * keyed by absolute library path.
	 */
	private val sessions = ConcurrentHashMap<String, Pkcs11ProbeLib>()

	/**
	 * Library paths that failed subprocess probing (crash, timeout, or in-process
	 * initialization failure) and must never be loaded in-process.
	 */
	private val crashedLibraries = ConcurrentHashMap.newKeySet<String>()

	/**
	 * Lock objects for one-time initialization of each library path.
	 */
	private val initLocks = ConcurrentHashMap<String, Any>()

	/**
	 * Single-thread executor used for all [probeInProcess] native calls.
	 *
	 * Reusing one thread avoids PKCS#11 thread-local storage re-initialization and
	 * prevents concurrent native calls that can deadlock when p11-kit-proxy and a
	 * direct library share the same underlying native code.
	 *
	 * Replaced by [replaceProbeExecutor] when a probe times out (thread stuck in
	 * native code).
	 */
	@Volatile
	private var probeExecutor: ExecutorService = createProbeExecutor()

	/**
	 * Guard for atomically replacing [probeExecutor] after a timeout.
	 */
	private val probeExecutorLock = Any()

	/**
	 * Register a library as safe for in-process use after a successful subprocess probe.
	 *
	 * Loads the library via JNA and calls `C_Initialize` on a **daemon thread** guarded
	 * by a [REGISTER_TIMEOUT_SECONDS] timeout.  If initialization succeeds (or returns
	 * `CKR_CRYPTOKI_ALREADY_INITIALIZED`), the handle is stored for subsequent
	 * [probeInProcess] calls.  If initialization fails or the native call hangs beyond
	 * the timeout, the library is added to [crashedLibraries] instead and the daemon
	 * thread is abandoned.
	 *
	 * Uses a per-call daemon thread (not the shared [probeExecutor]) because registration
	 * runs once per library during warmup and multiple libraries may need independent
	 * timeout handling in parallel.
	 *
	 * This method is idempotent — calling it multiple times for the same path is safe.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun registerSafe(libraryPath: String) {
		if (sessions.containsKey(libraryPath) || crashedLibraries.contains(libraryPath)) return

		val lock = initLocks.getOrPut(libraryPath) { Any() }
		synchronized(lock) {
			if (sessions.containsKey(libraryPath) || crashedLibraries.contains(libraryPath)) return

			val future = CompletableFuture<Pkcs11ProbeLib?>()
			thread(isDaemon = true, name = "pkcs11-init-${File(libraryPath).name}") {
				try {
					@Suppress("UNCHECKED_CAST")
					val lib = Native.load(libraryPath, Pkcs11ProbeLib::class.java) as Pkcs11ProbeLib
					val rv = lib.C_Initialize(null).toLong()
					if (rv != CKR_OK && rv != CKR_CRYPTOKI_ALREADY_INITIALIZED) {
						future.complete(null)
					} else {
						future.complete(lib)
					}
				} catch (e: Throwable) {
					future.completeExceptionally(e)
				}
			}

			try {
				val lib = future.get(REGISTER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				if (lib != null) {
					sessions[libraryPath] = lib
					logger.info { "PKCS#11 session established in-process for '$libraryPath'" }
				} else {
					logger.warn { "C_Initialize for '$libraryPath' returned non-OK — not loading in-process" }
					crashedLibraries.add(libraryPath)
				}
			} catch (_: TimeoutException) {
				logger.warn {
					"Native initialization for '$libraryPath' timed out after ${REGISTER_TIMEOUT_SECONDS}s — " +
							"marking as crashed (daemon thread abandoned)"
				}
				crashedLibraries.add(libraryPath)
			} catch (e: Exception) {
				logger.warn(e) { "Failed to initialize '$libraryPath' in-process — marking as crashed" }
				crashedLibraries.add(libraryPath)
			}
		}
	}

	/**
	 * Mark a library as unsafe for in-process loading.
	 *
	 * Called when a subprocess probe crashes (SIGSEGV, SIGABRT) or times out.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun registerCrashed(libraryPath: String) {
		crashedLibraries.add(libraryPath)
		logger.debug { "Marked '$libraryPath' as crashed — will not load in-process" }
	}

	/**
	 * Probe a library for token identities using the persistent in-process session.
	 *
	 * Native PKCS#11 calls are submitted to the shared [probeExecutor] — a single
	 * persistent daemon thread that is reused across calls.  This is critical because:
	 * - PKCS#11 middleware uses **thread-local storage**: a brand-new thread triggers
	 *   expensive per-thread re-initialization (USB bus scan) on every call.  Reusing
	 *   the same thread keeps TLS warm and probes fast (milliseconds).
	 * - p11-kit-proxy internally delegates to the same native libraries probed directly;
	 *   **concurrent calls** from separate threads can deadlock.  A single thread
	 *   serializes all native calls, eliminating the deadlock.
	 *
	 * If a probe times out (middleware hung), the stuck executor thread is abandoned and
	 * [replaceProbeExecutor] creates a fresh one so subsequent probes are not blocked.
	 *
	 * Only `C_GetSlotList` + `C_GetTokenInfo` are called — **no** `C_Initialize` or
	 * `C_Finalize`.  `C_Initialize` runs exactly once during warmup; subsequent probes
	 * rely solely on slot scanning for speed.  When the in-process session has no slots
	 * to report (e.g., the token was not connected during startup and the middleware
	 * fixed the slot list at `C_Initialize` time), this method returns `null` so the
	 * caller can fall back to subprocess probing, which runs its own `C_Initialize` in
	 * an isolated process and correctly detects hot-inserted tokens.
	 *
	 * **Intentional code duplication**: the slot-enumeration logic is near-identical to the
	 * standalone [probeTokenIdentities] function.  The duplication is deliberate:
	 * [probeTokenIdentities] runs inside an isolated [Pkcs11ProbeWorker] subprocess where
	 * native crashes are contained, whereas this method operates on a pre-initialized
	 * in-process session.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 * @return Token identities found, or `null` when no in-process session exists for
	 *   this library, slot scanning returned no results, the probe timed out, or an
	 *   exception occurred.  The caller should fall back to subprocess probing in all
	 *   `null` cases.
	 */
	@Suppress("DuplicatedCode")
	fun probeInProcess(libraryPath: String): List<Pkcs11TokenIdentity>? {
		val lib = sessions[libraryPath] ?: return null

		val executor = probeExecutor
		val future = executor.submit(Callable { queryTokenSlots(lib, libraryPath) })

		return try {
			future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).ifEmpty { null }
		} catch (_: TimeoutException) {
			logger.warn {
				"In-process probe for '$libraryPath' timed out after ${PROBE_TIMEOUT_SECONDS}s — " +
						"falling back to subprocess"
			}
			future.cancel(true)
			replaceProbeExecutor(executor)
			null
		} catch (e: Exception) {
			logger.warn(e) { "In-process probe failed for '$libraryPath' — falling back to subprocess" }
			null
		}
	}

	/**
	 * Replace the probe executor after a timeout, abandoning the stuck thread.
	 *
	 * Only replaces if the current executor is the same instance that timed out,
	 * preventing redundant replacements when multiple callers time out concurrently.
	 *
	 * @param stuckExecutor The executor instance whose thread is stuck in native code.
	 */
	private fun replaceProbeExecutor(stuckExecutor: ExecutorService) {
		synchronized(probeExecutorLock) {
			if (probeExecutor === stuckExecutor) {
				logger.warn { "Replacing stuck probe executor with a fresh thread" }
				probeExecutor = createProbeExecutor()
			}
		}
	}

	/**
	 * Query for slots that currently have a token present and read their identities.
	 *
	 * Calls `C_GetSlotList(tokenPresent=CK_TRUE)` to get the slot IDs, then
	 * `C_GetTokenInfo` on each slot to read the token label and serial number.
	 *
	 * @param lib The pre-initialized JNA handle for the PKCS#11 library.
	 * @param libraryPath Absolute library path, stored in each returned [Pkcs11TokenIdentity].
	 * @return Token identities found, or an empty list when no tokens are inserted or
	 *   any PKCS#11 call fails.
	 */
	@Suppress("DuplicatedCode")
	private fun queryTokenSlots(lib: Pkcs11ProbeLib, libraryPath: String): List<Pkcs11TokenIdentity> {
		val countMem = Memory(Native.LONG_SIZE.toLong()).also { it.clear() }
		if (lib.C_GetSlotList(1.toByte(), null, countMem).toLong() != CKR_OK) return emptyList()

		val slotCount = countMem.getNativeLong(0).toLong().toInt()
		if (slotCount <= 0) return emptyList()

		val slotsMem = Memory(slotCount.toLong() * Native.LONG_SIZE)
		slotsMem.clear()
		countMem.setNativeLong(0, NativeLong(slotCount.toLong()))
		if (lib.C_GetSlotList(1.toByte(), slotsMem, countMem).toLong() != CKR_OK) return emptyList()

		val results = mutableListOf<Pkcs11TokenIdentity>()
		for (i in 0 until slotCount) {
			val slotId = slotsMem.getNativeLong(i.toLong() * Native.LONG_SIZE)
			val tokenInfo = Memory(CK_TOKEN_INFO_SIZE.toLong())
			tokenInfo.clear()
			if (lib.C_GetTokenInfo(slotId, tokenInfo).toLong() != CKR_OK) continue

			val label = tokenInfo.getByteArray(CK_TOKEN_INFO_LABEL_OFFSET.toLong(), CK_TOKEN_INFO_LABEL_LEN)
				.trimPkcs11Field()
			val serial = tokenInfo.getByteArray(CK_TOKEN_INFO_SERIAL_OFFSET.toLong(), CK_TOKEN_INFO_SERIAL_LEN)
				.trimPkcs11Field()

			if (serial.isNotBlank()) {
				results += Pkcs11TokenIdentity(
					label = label.ifBlank { serial },
					serialNumber = serial,
					libraryPath = libraryPath,
				)
			}
		}
		return results
	}

	/**
	 * Whether a persistent in-process session exists for the given library.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun hasSession(libraryPath: String): Boolean = sessions.containsKey(libraryPath)

	/**
	 * Whether the given library is known to crash and must not be loaded in-process.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun isCrashed(libraryPath: String): Boolean = crashedLibraries.contains(libraryPath)

	private companion object {
		val logger = KotlinLogging.logger {}

		/**
		 * Counter for naming probe executor daemon threads.
		 */
		private val probeThreadCounter = AtomicInteger(0)

		/**
		 * Create a single-thread executor backed by a daemon thread for PKCS#11 probing.
		 *
		 * The thread name includes an incrementing counter so that abandoned threads
		 * (from timeout replacements) are distinguishable in thread dumps.
		 */
		private fun createProbeExecutor(): ExecutorService =
			Executors.newSingleThreadExecutor { runnable ->
				Thread(runnable, "pkcs11-probe-${probeThreadCounter.incrementAndGet()}").apply {
					isDaemon = true
				}
			}

		/**
		 * Maximum time in seconds to wait for `Native.load` + `C_Initialize` to complete
		 * during [registerSafe].
		 *
		 * Generous enough for slow USB middleware that needs hardware enumeration during
		 * `C_Initialize` (typically 1–3 s), but strict enough to unblock warmup when
		 * middleware is unresponsive (e.g., corrupted lock files after a force-kill).
		 */
		const val REGISTER_TIMEOUT_SECONDS = 10L

		/**
		 * Maximum time in seconds to wait for the slot refresh + `C_GetSlotList` +
		 * `C_GetTokenInfo` sequence to complete during [probeInProcess].
		 *
		 * These calls are lightweight on healthy middleware (< 100 ms).  The 5-second
		 * cap is a safety net for unresponsive middleware without making the UI feel
		 * unacceptably slow.
		 */
		const val PROBE_TIMEOUT_SECONDS = 5L
	}
}
