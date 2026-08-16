package cz.pizavo.omnisign.data.service

/**
 * Runs isolated PKCS#11 worker subprocesses and interprets their outcome.
 *
 * The single seam through which discovery, warmup, diagnostics and token-presence
 * checks reach the process-isolated workers — `Pkcs11ProbeWorker` for probing a single
 * library and `Pkcs11ModuleDiscoveryWorker` for enumerating p11-kit-registered modules.
 * Injected as a Koin singleton so consumers depend on this abstraction rather than on
 * top-level functions — replacing the former `tokenProber` lambda +
 * `mockkStatic(::runProbeSubprocess)` coupling, and consolidating probe-spawn logic that
 * was previously split across `Pkcs11Discoverer` and `Pkcs11SubprocessResult`.
 */
interface Pkcs11Prober {

	/**
	 * Probe [libraryPath] for the identities of currently-inserted tokens, using this
	 * prober's configured timeout.  Returns an empty list on crash, timeout, unresolved
	 * command, or no tokens — never throws.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun probeIdentities(libraryPath: String): List<Pkcs11TokenIdentity>

	/**
	 * Spawn a `Pkcs11ProbeWorker` subprocess for [libraryPath] and return its classified
	 * outcome, or `null` when no probe command can be resolved.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 * @param timeoutSeconds Wall-clock kill timeout; only reached when the process hangs.
	 */
	fun runProbe(
		libraryPath: String,
		timeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
	): Pkcs11SubprocessResult?

	/**
	 * Diagnostic-only variant of [runProbe] that additionally requests a no-`C_Login`
	 * certificate enumeration (`--certs`).  Never invoked by discovery or warmup.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 * @param timeoutSeconds Wall-clock kill timeout; only reached when the process hangs.
	 */
	fun runCertProbe(
		libraryPath: String,
		timeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
	): Pkcs11SubprocessResult?

	/**
	 * Parse a probe subprocess's `stdout` into [Pkcs11TokenIdentity] rows.  Exposed so a
	 * caller that already holds a [Pkcs11SubprocessResult.Success] (warmup) can reuse the
	 * single parser without re-spawning.
	 *
	 * @param stdout Captured standard output of the probe subprocess.
	 * @param libraryPath Absolute path of the library that produced [stdout]; recorded on
	 *   each resulting identity for traceability.
	 */
	fun parseIdentities(stdout: String, libraryPath: String): List<Pkcs11TokenIdentity>

	/**
	 * Enumerate the absolute paths of the PKCS#11 modules registered with the system's
	 * p11-kit, by spawning an isolated `Pkcs11ModuleDiscoveryWorker` subprocess that loads
	 * (but does not initialise) the configured modules via libp11-kit.
	 *
	 * Returns an empty list when libp11-kit is unavailable, no module is registered, the
	 * command cannot be resolved, or the subprocess crashes or times out — never throws.  The
	 * paths are unfiltered; callers (notably [Pkcs11LibP11KitModuleResolver]) drop non-signing
	 * modules such as the p11-kit trust policy module.
	 *
	 * @param timeoutSeconds Wall-clock kill timeout; only reached when the subprocess hangs.
	 */
	fun discoverModulePaths(timeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS): List<String>

	companion object {

		/**
		 * Default timeout (seconds) for a single probe subprocess.  Single source of
		 * truth shared by discovery, warmup, diagnostics and token-presence checks; a
		 * safety net for middleware that hangs (crashed probes exit immediately).
		 */
		const val DEFAULT_PROBE_TIMEOUT_SECONDS = 30L

		/**
		 * Sentinel line every worker prints, and flushes, after its last output line.
		 *
		 * It makes output completeness observable **without** waiting for the child to exit,
		 * which some middleware makes impossible: a PKCS#11 library that has talked to a live
		 * card can deadlock the process inside the C runtime's `DLL_PROCESS_DETACH` handling
		 * (the Czech eObčanka libraries do exactly this), a state no `System.exit` or
		 * `Runtime.halt` in the child can escape, and one that never closes the child's stdout
		 * so the parent never sees EOF either.  Seeing this line lets
		 * [Pkcs11SubprocessProber] accept a complete payload and kill the wedged child, while
		 * output truncated by a native crash — which cannot have printed the sentinel — is
		 * still correctly classified as a crash.
		 *
		 * Deliberately tab-free so it can never be mistaken for a `label\tserialNumber\tslotId`
		 * identity row, and prefixed so it cannot collide with a module path.
		 */
		const val OUTPUT_TERMINATOR = "__OMNISIGN_WORKER_DONE__"
	}
}
