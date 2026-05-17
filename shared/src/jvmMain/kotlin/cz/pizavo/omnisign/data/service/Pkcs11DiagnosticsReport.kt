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
 * @property noLoginEnumeration Per-PKCS#11-token result of attempting a SunPKCS#11
 *   `KeyStore` enumeration **without** `C_Login` (no PIN).  This is the "Route A" probe:
 *   it answers, against the real token, whether signing certificates are visible as
 *   public objects before any authentication — i.e. whether OmniSign could list certs
 *   the way Adobe does and defer the PIN to signing.  Empty when no PKCS#11 token was
 *   discovered.
 * @property rawNoLoginCertificates Per-library result of a raw, out-of-process
 *   `C_FindObjects(CKO_CERTIFICATE)` enumeration with no `C_Login` — the authoritative
 *   "Route A premise" check through OmniSign's own JNA stack.  Unlike [noLoginEnumeration]
 *   (which goes through SunPKCS#11's `KeyStore` and is blocked for login-required tokens),
 *   this reads certificate objects directly and exposes the exact `CKA_ID` / label a
 *   future no-login-list → logged-in-sign handoff would join on.
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
	val noLoginEnumeration: List<NoLoginEnumeration>,
	val rawNoLoginCertificates: List<RawNoLoginCertScan>,
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
	 * Each list contains entries that *would* be merged by [Pkcs11CandidateCollector.collectCandidates];
	 * deduplication and filtering are reflected only in [Pkcs11DiagnosticsReport.mergedCandidates].
	 *
	 * @property osNative Result of [Pkcs11CandidateCollector.discoverViaOs] — PC/SC + registry on Windows,
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
	 * @property name Human-readable middleware label as derived by [Pkcs11CandidateCollector.deriveMiddlewareName].
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
		 * - [NO_COMMAND] — [Pkcs11Prober] could not resolve a usable probe executable.
		 */
		enum class Outcome { SUCCESS, CRASHED, TIMED_OUT, NO_COMMAND }
	}

	/**
	 * A single token identity observed by the probe (label + serial + slot).
	 *
	 * @property label Token label as reported by `C_GetTokenInfo` (max 32 chars, padding stripped).
	 * @property serialNumber Token serial number as reported by `C_GetTokenInfo` (max 16 chars,
	 *   padding stripped).
	 * @property slotId PKCS#11 slot identifier where the token was observed.  Surfaced so users
	 *   diagnosing PIN-rejection issues can see which slot SunPKCS11 will be pinned to;
	 *   particularly relevant on Linux p11-kit-proxy installs where slot 0 is rarely the
	 *   user-PIN slot.
	 */
	data class Identity(val label: String, val serialNumber: String, val slotId: Long = 0L)

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

	/**
	 * Outcome of attempting an unauthenticated (no-`C_Login`) SunPKCS#11 `KeyStore`
	 * enumeration of one PKCS#11 token — the "Route A" experiment.
	 *
	 * The probe configures a SunPKCS#11 provider for [libraryPath]/[slotId] and calls
	 * `KeyStore("PKCS11").load(null, null)`, which opens a public session **without**
	 * sending the user PIN.  What [entries] then contains tells us, on this specific
	 * token, whether the signing certificate is a public object readable before
	 * authentication (so the PIN could be deferred to signing, matching Adobe) or
	 * whether enumeration itself is gated behind login on this token/JDK.
	 *
	 * @property tokenName Display name of the token as discovery would emit it.
	 * @property libraryPath Absolute path of the PKCS#11 module the provider was pointed at.
	 * @property slotId Slot the provider was pinned to, or `null` when discovery did not
	 *   resolve a slot (the provider then falls back to its default slot selection).
	 * @property loaded `true` when `KeyStore.load(null, null)` returned without throwing —
	 *   i.e. an unauthenticated session was usable.  `false` records that the no-login
	 *   path is not viable on this token, with the reason in [error].
	 * @property error Failure detail when [loaded] is `false` (library missing, provider
	 *   unavailable, or the exception thrown by `load`); `null` on success.
	 * @property entries Keystore entries visible **without** a PIN; empty when [loaded] is
	 *   `true` but the token exposes nothing publicly.
	 */
	data class NoLoginEnumeration(
		val tokenName: String,
		val libraryPath: String,
		val slotId: Long?,
		val loaded: Boolean,
		val error: String?,
		val entries: List<NoLoginEntry>,
	)

	/**
	 * A single keystore entry observed during the no-login enumeration.
	 *
	 * The decisive field is [isCertificateEntry] with a non-null [subjectDN]: a signing
	 * certificate showing up here means it is a public object readable without the PIN.
	 * [isKeyEntry] is expected to be `false` pre-login (private keys stay private until
	 * `C_Login`); a `true` here would itself be a notable token quirk.
	 *
	 * @property alias Keystore alias as surfaced by SunPKCS#11.
	 * @property isKeyEntry `KeyStore.isKeyEntry(alias)` — whether a private-key entry is
	 *   visible without login (normally `false`).
	 * @property isCertificateEntry `KeyStore.isCertificateEntry(alias)` — whether the alias
	 *   resolves to a certificate-only (trusted-cert) entry.
	 * @property subjectDN Subject DN of the entry's X.509 certificate; `null` when the
	 *   alias has no readable certificate.
	 * @property issuerDN Issuer DN of the entry's X.509 certificate; `null` when absent.
	 * @property serialNumber Certificate serial number (decimal string); `null` when absent.
	 */
	data class NoLoginEntry(
		val alias: String,
		val isKeyEntry: Boolean,
		val isCertificateEntry: Boolean,
		val subjectDN: String?,
		val issuerDN: String?,
		val serialNumber: String?,
	)

	/**
	 * Result of the raw, out-of-process no-`C_Login` certificate enumeration for one
	 * PKCS#11 library.
	 *
	 * @property libraryPath Absolute path of the probed PKCS#11 module.
	 * @property subprocessSucceeded Whether the `--certs` probe subprocess exited cleanly.
	 *   `false` means it crashed, timed out, or could not be launched (see the Probes
	 *   section for the reason); [certificates] is then empty for that reason, not because
	 *   the token has no public certs.
	 * @property certificates Certificates readable without a PIN.  Empty with
	 *   [subprocessSucceeded] `true` means the token exposes no public certificate objects
	 *   (they are private — Route A would not work for this token).
	 */
	data class RawNoLoginCertScan(
		val libraryPath: String,
		val subprocessSucceeded: Boolean,
		val certificates: List<RawNoLoginCert>,
	)

	/**
	 * One certificate read directly from the token without authenticating.
	 *
	 * The presence of an end-entity signing certificate here is the verified proof that
	 * the cert is a public object, so OmniSign could list it with no PIN and defer
	 * authentication to signing.
	 *
	 * @property subjectDN Subject DN (RFC 2253).
	 * @property issuerDN Issuer DN (RFC 2253).
	 * @property serialNumber Certificate serial number (decimal string).
	 * @property ckaId Lower-case hex of the object's `CKA_ID`; the join key a future
	 *   no-login-list → logged-in-sign handoff would match against the private key.
	 *   Empty when the object has no `CKA_ID`.
	 * @property label The object's `CKA_LABEL` (decoded UTF-8); empty when absent.
	 * @property slotId Slot the certificate was found in.
	 */
	data class RawNoLoginCert(
		val subjectDN: String,
		val issuerDN: String,
		val serialNumber: String,
		val ckaId: String,
		val label: String,
		val slotId: Long,
	)
}