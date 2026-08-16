package cz.pizavo.omnisign.data.service

import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Standalone entry point for out-of-process enumeration of the PKCS#11 modules registered
 * with p11-kit, via libp11-kit.
 *
 * Invoked as a subprocess by [Pkcs11SubprocessProber.discoverModulePaths] (and, in jpackage
 * images without a bundled `java` binary, via the native launcher's `discover-modules`
 * subcommand).  It replaces the previous hard-coded `p11-kit-proxy.so` path guessing on
 * Linux: instead of betting on where the proxy lives, it asks libp11-kit itself which
 * modules are configured, so discovery follows the system's own PKCS#11 registry rather than
 * a baked-in list that drifts across distributions.
 *
 * The worker binds the minimal [P11KitLib] surface, calls `p11_kit_modules_load` (which
 * `dlopen`s the configured modules **without** `C_Initialize`), reads each module's resolved
 * filename via `p11_kit_module_get_filename`, and prints one path per line to stdout.
 * Because no module is initialised, it never reaches the phase where faulty middleware hangs
 * or crashes; and because it runs in its own process, even a misbehaving `dlopen`
 * constructor is confined here rather than the host JVM.
 *
 * Exit behaviour:
 * - Normal completion prints zero or more paths, then [Pkcs11Prober.OUTPUT_TERMINATOR] to mark
 *   the payload complete, and exits `0`.  Empty output simply means no modules are registered,
 *   or libp11-kit is not installed — both yield no candidates.
 * - A fatal native fault (a module's `dlopen` constructor crashing the process) terminates
 *   with a signal; the parent classifies that as a crash and contributes no candidates.
 */
object Pkcs11ModuleDiscoveryWorker {

	/**
	 * Enumerate p11-kit-registered module paths and print one per line to stdout.
	 *
	 * @param args Ignored; the worker takes no arguments.
	 */
	@JvmStatic
	fun main(args: Array<String>) {
		for (path in discoverModulePaths()) {
			println(path)
		}
		println(Pkcs11Prober.OUTPUT_TERMINATOR)
		System.out.flush()
	}

	/**
	 * Load libp11-kit, enumerate the configured modules, and collect their resolved
	 * filenames.
	 *
	 * Returns an empty list when libp11-kit is unavailable or no module is configured.  Any
	 * Java-level failure (missing library, missing symbol) is reported to `stderr` — which the
	 * parent surfaces in its logs — and then swallowed so the worker exits cleanly with
	 * whatever it managed to collect; a native crash is left to terminate the process so the
	 * parent can classify it as a crash.
	 */
	private fun discoverModulePaths(): List<String> {
		val lib = loadLibrary()
		if (lib == null) {
			System.err.println("libp11-kit not found (tried: ${LIBRARY_NAME_CANDIDATES.joinToString(", ")})")
			return emptyList()
		}
		return runCatching {
			enumerateModulePaths(lib)
		}.getOrElse { e ->
			System.err.println("libp11-kit module enumeration failed: ${e.message ?: e}")
			emptyList()
		}
	}

	/**
	 * Load the configured modules via [P11KitLib.p11_kit_modules_load] and read each one's
	 * resolved filename, walking the NULL-terminated `CK_FUNCTION_LIST**` array.
	 *
	 * @param lib The bound libp11-kit instance.
	 * @return Resolved module filenames; empty when `p11_kit_modules_load` returns null.
	 */
	private fun enumerateModulePaths(lib: P11KitLib): List<String> {
		val modules = lib.p11_kit_modules_load(null, P11_KIT_MODULE_LOAD_FLAGS_NONE)
		if (modules == null) {
			System.err.println("p11_kit_modules_load returned null — no modules loaded")
			return emptyList()
		}

		val paths = mutableListOf<String>()
		var index = 0
		while (true) {
			val module = modules.getPointer(index.toLong() * Native.POINTER_SIZE) ?: break
			val filename = runCatching { lib.p11_kit_module_get_filename(module) }.getOrNull()
			if (!filename.isNullOrBlank()) paths.add(filename)
			index++
		}
		return paths
	}

	/**
	 * Load libp11-kit under the names it ships as across distributions, tolerating the common
	 * case where only the versioned runtime soname (`libp11-kit.so.0`) is present and the
	 * unversioned developer symlink (`libp11-kit.so`) is not.
	 *
	 * @return the bound [P11KitLib], or `null` when no candidate name resolves.
	 */
	private fun loadLibrary(): P11KitLib? {
		for (name in LIBRARY_NAME_CANDIDATES) {
			val lib = runCatching { Native.load(name, P11KitLib::class.java) }.getOrNull()
			if (lib != null) return lib
		}
		return null
	}

	/**
	 * libp11-kit library names tried in order: the bare name (resolves when the developer
	 * symlink exists), the versioned runtime soname (always present when p11-kit is
	 * installed), and the explicit unversioned filename.
	 */
	private val LIBRARY_NAME_CANDIDATES = listOf("p11-kit", "libp11-kit.so.0", "libp11-kit.so")
}
