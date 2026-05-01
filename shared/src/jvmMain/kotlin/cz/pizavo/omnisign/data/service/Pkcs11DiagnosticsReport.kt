package cz.pizavo.omnisign.data.service

/**
 * Snapshot of everything the PKCS#11 discovery layer can observe on the host machine.
 *
 * Produced by [Pkcs11DiagnosticsService.runDiagnostics] and rendered by the
 * `omnisign diagnose pkcs11` CLI subcommand.  The structure is intentionally flat and
 * platform-agnostic so the same report can be pretty-printed, serialised, or compared
 * across machines.
 *
 * @property environment JVM and operating-system facts that govern PKCS#11 discovery.
 * @property candidatesByLayer Per-source breakdown of where candidate library paths come from.
 * @property mergedCandidates Deduplicated candidate list as the discoverer would produce it.
 * @property p11Kit Out-of-process p11-kit truth (Linux only); `null` on other platforms or
 *   when no `p11-kit` / `pkg-config` binary is available.
 * @property pcscReaders Connected PC/SC readers and their card-presence state.  Empty when
 *   the PC/SC stack is unavailable (no `pcscd` on Linux; no smart-card service on Windows).
 *   Each entry is `"<reader name> — <card present (ATR …) | empty>"`.
 * @property probes Per-candidate subprocess probe outcomes with wall-clock timings.
 * @property tokens The final [cz.pizavo.omnisign.domain.service.TokenInfo] list as discovery would emit it.
 * @property totalElapsedMillis Total wall-clock duration of the diagnostics run, in milliseconds.
 */
data class Pkcs11DiagnosticsReport(
	val environment: Environment,
	val candidatesByLayer: CandidatesByLayer,
	val mergedCandidates: List<Candidate>,
	val p11Kit: P11KitTruth?,
	val pcscReaders: List<String>,
	val probes: List<ProbeOutcome>,
	val tokens: List<TokenSummary>,
	val totalElapsedMillis: Long,
) {

	/**
	 * JVM and operating-system facts captured at diagnostics time.
	 *
	 * @property osName Value of `os.name` system property (e.g. "Windows 11", "Linux").
	 * @property osArch Value of `os.arch` system property.
	 * @property osVersion Value of `os.version` system property.
	 * @property jvmIs64Bit Whether the JVM is running in 64-bit mode (per `sun.arch.data.model`).
	 * @property javaVersion Value of `java.version` system property.
	 * @property javaHome Resolved `java.home` directory.
	 * @property classpathChars Length of `java.class.path` in characters; 0 when unset
	 *   (a known signal that the subprocess probe must use the native-launcher fallback).
	 * @property nativeAccessFlagPresent Whether `--enable-native-access=ALL-UNNAMED` was
	 *   resolved on the command line (heuristic via `RuntimeMXBean.inputArguments`).
	 */
	data class Environment(
		val osName: String,
		val osArch: String,
		val osVersion: String,
		val jvmIs64Bit: Boolean,
		val javaVersion: String,
		val javaHome: String,
		val classpathChars: Int,
		val nativeAccessFlagPresent: Boolean,
	)

	/**
	 * Candidate library paths grouped by the source layer that produced them.
	 *
	 * Each list contains entries that *would* be merged by [Pkcs11Discoverer.collectCandidates];
	 * deduplication and filtering are reflected only in [Pkcs11DiagnosticsReport.mergedCandidates].
	 *
	 * @property osNative Result of [Pkcs11Discoverer.discoverViaOs] — PC/SC + registry on Windows,
	 *   `security`/`pluginkit`/`.module` on macOS, p11-kit / dir scan / `.module` on Linux.
	 * @property dropDir Files under `<appData>/omnisign/pkcs11/` whose names match the
	 *   PKCS#11 filename heuristic.
	 * @property userSupplied Entries from `GlobalConfig.customPkcs11Libraries` whose path exists.
	 */
	data class CandidatesByLayer(
		val osNative: List<Candidate>,
		val dropDir: List<Candidate>,
		val userSupplied: List<Candidate>,
	)

	/**
	 * A single PKCS#11 library candidate path with file-existence metadata.
	 *
	 * @property name Human-readable middleware label as derived by [Pkcs11Discoverer.deriveMiddlewareName].
	 * @property path Absolute path on the local filesystem.
	 * @property exists `true` when the path resolves to an existing regular file at scan time.
	 * @property sizeBytes File size in bytes when the path is a regular file; `null` otherwise.
	 */
	data class Candidate(
		val name: String,
		val path: String,
		val exists: Boolean,
		val sizeBytes: Long?,
	)

	/**
	 * Output captured from the system `p11-kit` toolchain to provide an authoritative
	 * cross-check for our own discovery.
	 *
	 * Each field is `null` when the corresponding command was not available, timed out,
	 * or exited non-zero.
	 *
	 * @property version Output of `p11-kit --version` (typically a single line such as
	 *   "p11-kit 0.25.5").
	 * @property pkgConfigProxyPath Output of `pkg-config --variable=proxy_module p11-kit-1`,
	 *   the canonical absolute path of the proxy module on this system.
	 * @property listModulesOutput Output of `p11-kit list-modules` — registered modules and
	 *   their paths as seen by p11-kit itself.
	 * @property listTokensOutput Output of `p11-kit list-tokens` — token instances as seen
	 *   by p11-kit (the closest thing to a ground-truth answer).
	 */
	data class P11KitTruth(
		val version: String?,
		val pkgConfigProxyPath: String?,
		val listModulesOutput: String?,
		val listTokensOutput: String?,
	)

	/**
	 * Outcome of a single subprocess probe of one PKCS#11 library, with timing.
	 *
	 * @property name Human-readable middleware label (mirrors [Candidate.name]).
	 * @property path Absolute path of the probed library.
	 * @property outcome Coarse-grained result classification.
	 * @property totalMillis Total wall-clock time from spawning the subprocess to receiving
	 *   its exit notification, in milliseconds.  Includes JVM cold-start in addition to
	 *   `C_Initialize` / slot enumeration cost — useful precisely because that is the cost
	 *   the discovery layer pays today.
	 * @property pid PID of the spawned subprocess; `null` only for [Outcome.NO_COMMAND].
	 * @property exitCode Subprocess exit code; `null` for [Outcome.SUCCESS] / [Outcome.TIMED_OUT]
	 *   / [Outcome.NO_COMMAND].  Values > 128 typically encode a POSIX signal number plus 128.
	 * @property stderrSnippet Truncated stderr captured from a crashed subprocess; `null` otherwise.
	 * @property identities Token identities parsed from the subprocess `stdout` when [outcome]
	 *   is [Outcome.SUCCESS]; empty otherwise.
	 */
	data class ProbeOutcome(
		val name: String,
		val path: String,
		val outcome: Outcome,
		val totalMillis: Long,
		val pid: Long?,
		val exitCode: Int?,
		val stderrSnippet: String?,
		val identities: List<Identity>,
	) {
		/**
		 * Coarse result of running a [Pkcs11ProbeWorker] subprocess.
		 *
		 * - [SUCCESS] — exit code 0; identity output (possibly empty when no card is inserted).
		 * - [CRASHED] — non-zero exit, often a SIGSEGV from misbehaving middleware.
		 * - [TIMED_OUT] — subprocess did not exit within the configured timeout and was killed.
		 * - [NO_COMMAND] — [resolveProbeCommand] could not find a usable executable.
		 */
		enum class Outcome { SUCCESS, CRASHED, TIMED_OUT, NO_COMMAND }
	}

	/**
	 * A single token identity observed by the probe (label + serial).
	 *
	 * @property label Token label as reported by `C_GetTokenInfo` (max 32 chars, padding stripped).
	 * @property serialNumber Token serial number as reported by `C_GetTokenInfo` (max 16 chars,
	 *   padding stripped).
	 */
	data class Identity(val label: String, val serialNumber: String)

	/**
	 * Compact projection of [cz.pizavo.omnisign.domain.service.TokenInfo] for the diagnostics
	 * report, omitting fields not relevant to PKCS#11 troubleshooting.
	 *
	 * @property id Stable token identifier (`pkcs11-<serial>` or fallback by file name).
	 * @property name Display name shown to the user.
	 * @property type [cz.pizavo.omnisign.domain.model.config.enums.TokenType] name.
	 * @property path PKCS#11 library path; `null` for OS-native tokens (Windows-MY, Keychain).
	 * @property requiresPin Whether this token requires interactive PIN entry.
	 */
	data class TokenSummary(
		val id: String,
		val name: String,
		val type: String,
		val path: String?,
		val requiresPin: Boolean,
	)
}