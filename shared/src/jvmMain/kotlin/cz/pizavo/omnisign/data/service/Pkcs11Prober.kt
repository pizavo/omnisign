package cz.pizavo.omnisign.data.service

/**
 * Runs isolated PKCS#11 probe subprocesses and interprets their outcome.
 *
 * The single seam through which discovery, warmup, diagnostics and token-presence
 * checks reach the (process-isolated) `Pkcs11ProbeWorker`.  Injected as a Koin
 * singleton so consumers depend on this abstraction rather than on top-level
 * functions — replacing the former `tokenProber` lambda + `mockkStatic(::runProbeSubprocess)`
 * coupling, and consolidating probe-spawn logic that was previously split across
 * `Pkcs11Discoverer` and `Pkcs11SubprocessResult`.
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

	companion object {

		/**
		 * Default timeout (seconds) for a single probe subprocess.  Single source of
		 * truth shared by discovery, warmup, diagnostics and token-presence checks; a
		 * safety net for middleware that hangs (crashed probes exit immediately).
		 */
		const val DEFAULT_PROBE_TIMEOUT_SECONDS = 30L
	}
}
