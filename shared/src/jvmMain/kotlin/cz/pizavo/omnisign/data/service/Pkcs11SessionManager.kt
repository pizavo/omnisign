package cz.pizavo.omnisign.data.service

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

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
	 * Register a library as safe for in-process use after a successful subprocess probe.
	 *
	 * Loads the library via JNA and calls `C_Initialize`.  If initialization succeeds
	 * (or returns `CKR_CRYPTOKI_ALREADY_INITIALIZED`), the handle is stored for
	 * subsequent [probeInProcess] calls.  If initialization fails, the library is
	 * added to [crashedLibraries] instead.
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

			runCatching {
				@Suppress("UNCHECKED_CAST")
				val lib = Native.load(libraryPath, Pkcs11ProbeLib::class.java) as Pkcs11ProbeLib
				val rv = lib.C_Initialize(null).toLong()
				if (rv != CKR_OK && rv != CKR_CRYPTOKI_ALREADY_INITIALIZED) {
					logger.warn { "C_Initialize for '$libraryPath' returned 0x${rv.toString(16)} — not loading in-process" }
					crashedLibraries.add(libraryPath)
					return
				}
				sessions[libraryPath] = lib
				logger.info { "PKCS#11 session established in-process for '$libraryPath'" }
			}.onFailure { e ->
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
	 * Uses `C_GetSlotList(tokenPresent=CK_TRUE)` and `C_GetTokenInfo` to enumerate
	 * currently inserted tokens.  These calls are lightweight and complete in
	 * milliseconds — no `C_Initialize` overhead on subsequent calls.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 * @return Token identities found, empty list if no tokens are inserted, or `null`
	 *   when no in-process session exists for this library (caller should fall back to
	 *   subprocess probing).
	 */
	fun probeInProcess(libraryPath: String): List<Pkcs11TokenIdentity>? {
		val lib = sessions[libraryPath] ?: return null

		return runCatching {
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
					.toString(Charsets.UTF_8).trim()
				val serial = tokenInfo.getByteArray(CK_TOKEN_INFO_SERIAL_OFFSET.toLong(), CK_TOKEN_INFO_SERIAL_LEN)
					.toString(Charsets.UTF_8).trim()

				if (serial.isNotBlank()) {
					results += Pkcs11TokenIdentity(
						label = label.ifBlank { serial },
						serialNumber = serial,
						libraryPath = libraryPath,
					)
				}
			}
			results
		}.onFailure { e ->
			logger.warn(e) { "In-process probe failed for '$libraryPath' — returning empty" }
		}.getOrDefault(emptyList())
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
	}
}

