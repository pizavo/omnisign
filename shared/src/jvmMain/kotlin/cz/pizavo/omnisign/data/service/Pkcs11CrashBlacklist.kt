package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Records PKCS#11 libraries that crashed during subprocess validation so they are never
 * loaded in-process for the lifetime of the JVM.
 *
 * Replaces the former in-process JNA session machinery: in-process loading of vendor
 * middleware created a second PKCS#11 consumer alongside SunPKCS11 (which DSS uses for
 * signing), and the cohabitation was a recurring source of subtle interaction bugs.  The
 * remaining role is the crash blacklist — libraries whose subprocess probe terminated with
 * a non-zero exit code (typically `SIGSEGV` / `SIGABRT`) are kept here so [Pkcs11Discoverer]
 * can short-circuit any further attempt without spawning another subprocess.
 *
 * **Timeouts are intentionally not blacklisted** — see [Pkcs11WarmupService.warmupSingleLibrary].
 * A transient hang during warmup should not permanently disable a healthy library; discovery
 * subprocess-probes on demand instead.
 *
 * Thread-safety is provided by [ConcurrentHashMap.newKeySet], so concurrent reads from
 * [isCrashed] and writes from [registerCrashed] are safe.
 */
class Pkcs11CrashBlacklist {

	/**
	 * Library paths whose subprocess validation crashed.  Looked up by absolute path; never
	 * pruned for the lifetime of the JVM.
	 */
	private val crashedLibraries = ConcurrentHashMap.newKeySet<String>()

	/**
	 * Mark a library as unsafe for in-process loading.
	 *
	 * Called by [Pkcs11WarmupService] when a subprocess probe exits with a non-zero code
	 * (a native crash).  Subsequent calls to [Pkcs11Discoverer.probeLibrary] for the same
	 * path return an empty list immediately rather than spawning another doomed subprocess.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun registerCrashed(libraryPath: String) {
		crashedLibraries.add(libraryPath)
		logger.debug { "Marked '$libraryPath' as crashed — will not probe again in this session" }
	}

	/**
	 * Whether the given library has been recorded as crashed and must not be probed again.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun isCrashed(libraryPath: String): Boolean = crashedLibraries.contains(libraryPath)

	private companion object {
		val logger = KotlinLogging.logger {}
	}
}
