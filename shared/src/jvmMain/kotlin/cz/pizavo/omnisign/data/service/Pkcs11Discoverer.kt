package cz.pizavo.omnisign.data.service

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.P11_KIT_PROXY_PATHS
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.P11_STANDALONE_PATTERN
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.PKCS11_NAME_PATTERNS
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.nio.file.Path
import java.util.*

/**
 * Identity of a physical PKCS#11 token as reported by `C_GetTokenInfo`.
 *
 * @property label Token label (up to 32 UTF-8 characters, space-padded by the PKCS#11 spec).
 * @property serialNumber Token serial number (up to 16 characters, space-padded).
 * @property libraryPath Absolute path of the PKCS#11 middleware library that reported this token.
 */
data class Pkcs11TokenIdentity(
    val label: String,
    val serialNumber: String,
    val libraryPath: String,
)

/**
 * Default timeout (in seconds) for probing a single PKCS#11 library.
 *
 * Acts as a safety net for middleware that hangs without responding.
 * Probes that crash (e.g., SIGSEGV, SIGABRT) exit immediately with a
 * non-zero code and are handled without waiting for this timeout.
 */
internal const val DEFAULT_PROBE_TIMEOUT_SECONDS = 30L

/**
 * Discovers PKCS#11 middleware libraries available on the current system and resolves
 * them to physical token identities.
 *
 * Discovery is layered from most to least authoritative:
 * 1. OS-native sources ([discoverViaOs]) — PC/SC on Windows, `security`/`pluginkit` on macOS,
 *    p11-kit on Linux.
 * 2. App-data drop directory — any PKCS#11-named file placed under
 *    `<appDataDir>/omnisign/pkcs11/`.
 * 3. User-supplied paths — entries from
 *    [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries].
 *
 * Duplicates are resolved first by canonical path, then by probing the actual hardware
 * token identity (label and serial number) via `C_GetTokenInfo`.  Multiple middleware DLLs
 * that report the same physical token serial produce a single [TokenInfo].
 *
 * @property probeTimeoutSeconds Maximum time in seconds to wait for a single PKCS#11 library
 *   probe subprocess before killing it.  Acts as a safety net for middleware that hangs without
 *   responding; probes that crash (SIGSEGV, SIGABRT) exit immediately and are handled without
 *   waiting for this timeout.  Defaults to [DEFAULT_PROBE_TIMEOUT_SECONDS].
 * @property sessionManager Optional persistent session manager for fast in-process probing.
 *   When a library has been pre-initialized via [Pkcs11WarmupService], [probeLibrary] uses
 *   the in-process path (milliseconds) instead of spawning a subprocess (~18 s).  When `null`
 *   or when no session has been registered yet (warmup still in flight), every probe falls
 *   back to the subprocess strategy.  Discovery never blocks on warmup completion — running
 *   while warmup is mid-flight just yields slower (subprocess-only) results for libraries
 *   that have not been registered yet.
 * @property discoveryGate Concurrency gate ensuring that at most one [discoverTokens] cycle runs
 *   at a time, with at most one additional cycle queued.  When a discovery is already in progress
 *   and a new request arrives, the running cycle's result is discarded and a fresh cycle executes
 *   so every caller receives the latest hardware state.  Defaults to a new instance for backward
 *   compatibility and tests.
 * @property tokenProber Strategy for probing PKCS#11 libraries for hardware token identities.
 *   Defaults to subprocess-based probing via [probeTokenIdentitiesViaSubprocess] to isolate
 *   native crashes (SIGSEGV) from the host JVM; override for testing.  The default lambda
 *   forwards [probeTimeoutSeconds] to the subprocess.
 */
class Pkcs11Discoverer(
    private val probeTimeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
    private val sessionManager: Pkcs11SessionManager? = null,
    private val discoveryGate: ConflatedProbeGate<List<TokenInfo>> = ConflatedProbeGate(),
    private val tokenProber: (String) -> List<Pkcs11TokenIdentity> = { path ->
        probeTokenIdentitiesViaSubprocess(path, probeTimeoutSeconds)
    },
) {

    /**
     * Probe a single PKCS#11 library for token identities using the configured strategy.
     *
     * When a [sessionManager] is available and the library has been initialized in-process
     * (via [Pkcs11WarmupService]), the probe uses the fast in-process path
     * (`C_GetSlotList` + `C_GetTokenInfo`, milliseconds).  Libraries that crashed during
     * warmup are skipped immediately.
     *
     * When the in-process session exists but slot scanning returns no tokens, this method
     * falls through to the [tokenProber] (subprocess) fallback.  This handles the
     * **hot-insert** scenario: PKCS#11 middleware (notably SafeNet for USB crypto tokens)
     * fixes the slot list at `C_Initialize` time; if the token was not connected during
     * startup, the in-process session has zero slots and cannot detect it.  The subprocess
     * runs its own `C_Initialize` in an isolated process and will discover the token.
     *
     * Callers such as [DssTokenService.probeTokenPresent] should use this method rather than
     * calling [probeTokenIdentitiesViaSubprocess] directly so that the in-process fast path,
     * crash avoidance, and any test overrides are applied consistently.
     *
     * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
     * @return Token identities found in the library, or an empty list when the library is
     *   unreachable, the probe times out, or no tokens are inserted.
     */
    fun probeLibrary(libraryPath: String): List<Pkcs11TokenIdentity> {
        if (sessionManager != null) {
            if (sessionManager.isCrashed(libraryPath)) {
                logger.debug { "Skipping crashed library '$libraryPath' (in-process)" }
                return emptyList()
            }
            val inProcess = sessionManager.probeInProcess(libraryPath)
            if (inProcess != null) {
                logger.debug { "In-process probe for '$libraryPath' returned ${inProcess.size} token(s)" }
                return inProcess
            }
            logger.debug { "In-process probe for '$libraryPath' returned null — falling back to subprocess" }
        }

        return tokenProber(libraryPath)
    }

    /**
     * Enumerate all unique candidate PKCS#11 library paths without probing them.
     *
     * This is the discovery-only first phase: OS-native sources, curated fallback paths,
     * the app-data drop directory, and user-supplied libraries are merged and deduplicated
     * by canonical path.  No subprocess is spawned and no PKCS#11 function is called.
     *
     * Used by [Pkcs11WarmupService] to obtain the candidate set for background initialization
     * and by [discoverTokens] as the first step before parallel probing.
     *
     * @param appDataPkcs11Dir Optional drop directory; every PKCS#11-named file found here is
     *   added to the candidate list without any config change.
     * @param userPkcs11Libraries Additional `(display name, path)` pairs supplied by the user.
     *   Only entries whose file exists on disk are included.
     * @return Deduplicated list of `(display name, absolute path)` pairs.
     */
    fun collectCandidates(
        appDataPkcs11Dir: File? = null,
        userPkcs11Libraries: List<Pair<String, String>> = emptyList(),
    ): List<Pair<String, String>> {
        val os = System.getProperty("os.name").lowercase()
        val jvmIs64Bit = System.getProperty("sun.arch.data.model") == "64"
        val seen = LinkedHashMap<String, Pair<String, String>>()

        fun merge(candidates: List<Pair<String, String>>) {
            for ((name, path) in candidates) {
                val canonical = runCatching { File(path).canonicalPath }.getOrElse { path }
                seen.putIfAbsent(canonical, name to path)
            }
        }

        merge(discoverViaOs(os, jvmIs64Bit))
        if (appDataPkcs11Dir != null && appDataPkcs11Dir.isDirectory) {
            merge(
                appDataPkcs11Dir
                    .listFiles { f -> f.isFile && isPkcs11FileName(f.name) }
                    ?.map { f -> deriveMiddlewareName(f.absolutePath) to f.absolutePath }
                    ?: emptyList()
            )
        }
        merge(userPkcs11Libraries.filter { (_, path) -> File(path).exists() })

        return seen.values.filterNot { (_, path) -> isSpyLibrary(File(path).name) }
    }

	/**
	 * Discover all PKCS#11 tokens available on the system.
	 *
	 * Discovery never blocks on [Pkcs11WarmupService] completion.  Each candidate library is
	 * probed via [probeLibrary], which uses the fast in-process path when [sessionManager]
	 * has a registered session for it and falls back to a subprocess otherwise.  If warmup
	 * is still in flight, libraries that have not yet been registered fall back to subprocess
	 * — slower but correct.
	 *
	 * Probing runs in parallel on [Dispatchers.IO] — one coroutine per unique library path —
	 * so that slow or unresponsive middleware does not delay discovery of other tokens.
	 *
	 * Deduplication is described in [buildTokenInfoList]: serial-number collapse with
	 * direct-before-proxy ordering, and libraries with no identities are dropped (no stub
	 * `TokenInfo` is emitted for an empty probe).
	 *
	 * Concurrency between multiple callers is managed by [discoveryGate]: at most one
	 * full discovery cycle runs at a time, with at most one queued behind it.  If a new
	 * request arrives while a cycle is in progress, the in-progress result is discarded
	 * and a fresh cycle runs so that every caller receives the latest hardware state.
	 *
	 * @param appDataPkcs11Dir Optional drop directory; every PKCS#11-named file found here is
	 *   added to the candidate list without any config change.
	 * @param userPkcs11Libraries Additional `(display name, path)` pairs supplied by the user.
	 *   Only entries whose file exists on disk are included.
	 */
	suspend fun discoverTokens(
		appDataPkcs11Dir: File? = null,
		userPkcs11Libraries: List<Pair<String, String>> = emptyList(),
	): List<TokenInfo> = discoveryGate.runOrCoalesce {
		val candidates = collectCandidates(appDataPkcs11Dir, userPkcs11Libraries)

		val probeResults = coroutineScope {
			candidates.map { (name, path) ->
				async(Dispatchers.IO) {
					Triple(name, path, probeLibrary(path))
				}
			}.awaitAll()
		}

		buildTokenInfoList(probeResults)
	}

	/**
	 * Apply the deduplication strategy described in [discoverTokens] to a pre-computed list
	 * of probed candidates and emit the resulting [TokenInfo] entries.
	 *
	 * Extracted as an internal helper so [Pkcs11DiagnosticsService] can reuse the same dedup
	 * logic when building its own report from sequentially timed probes — keeping a single
	 * source of truth for the proxy-vs-direct ordering and serial normalisation.
	 *
	 * Dedup rules:
	 * 1. **Identities required** — only libraries that returned at least one token identity
	 *    contribute to the result.  Libraries that probe successfully but expose no inserted
	 *    token are surfaced only via diagnostics, not as user-visible tokens.
	 * 2. **Serial number** — the same normalised serial collapses to a single entry; direct
	 *    libraries are processed before proxy paths so direct paths win.
	 *
	 * @param probedCandidates Triples of `(display name, absolute path, identities)` —
	 *   one per candidate library, in any order.
	 * @return Deduplicated [TokenInfo] list ready to surface to the caller.
	 */
	internal fun buildTokenInfoList(
		probedCandidates: List<Triple<String, String, List<Pkcs11TokenIdentity>>>,
	): List<TokenInfo> {
		val withIdentities = probedCandidates.filter { it.third.isNotEmpty() }
		val sortedWithIdentities = withIdentities.sortedBy { isProxyPath(it.second) }

		val result = mutableListOf<TokenInfo>()
		val seenSerials = mutableSetOf<String>()

		for ((_, path, identities) in sortedWithIdentities) {
			for (identity in identities) {
				if (seenSerials.add(normalizeSerial(identity.serialNumber))) {
					result += TokenInfo(
						id = "pkcs11-${identity.serialNumber}",
						name = identity.label,
						type = TokenType.PKCS11,
						path = path,
						requiresPin = true,
					)
				}
			}
		}

		return result
	}

    /**
     * Query OS-native sources for PKCS#11 middleware without touching the fallback list.
     *
     * - **Windows**: PC/SC via `SCardListReaders`, vendor registry trees, `System32` dir scan.
     * - **macOS**: `security list-smartcards`, `pluginkit -mAT com.apple.ctk.token`, p11-kit
     *   module files, standard library directory scan.
     * - **Linux**: p11-kit proxy (if present), standard library directory scan, p11-kit
     *   `*.module` files.
     *
     * Never throws; returns an empty list when the OS mechanism is unavailable.
     */
    internal fun discoverViaOs(
        os: String = System.getProperty("os.name").lowercase(),
        jvmIs64Bit: Boolean = System.getProperty("sun.arch.data.model") == "64",
    ): List<Pair<String, String>> {
        val linuxLibDirs = if (jvmIs64Bit) listOf(
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
            "/usr/lib",
            "/usr/lib64",
            "/usr/local/lib",
        ) else listOf("/usr/lib", "/usr/local/lib")

        val macLibDirs = listOf("/usr/local/lib", "/opt/homebrew/lib")

        return when {
            os.contains("win") -> discoverViaPcsc() +
                    discoverViaWindowsRegistry(jvmIs64Bit) +
                    discoverViaDirScan(jvmIs64Bit)

            os.contains("mac") -> discoverViaMacOsSecurity() +
                    discoverViaP11Kit() +
                    discoverViaLibDirs(macLibDirs)

            else -> discoverViaP11KitProxy() +
                    discoverViaLibDirs(linuxLibDirs) +
                    discoverViaP11Kit()
        }
    }

    /**
     * Return `true` when [fileName] (base name only) looks like a PKCS#11 provider library.
     *
     * Patterns checked:
     * - Exact substring matches against [PKCS11_NAME_PATTERNS] (e.g. `pkcs11`, `cryptoki`).
     * - A standalone `p11` token that is **not** immediately followed by a digit, via
     *   [P11_STANDALONE_PATTERN].  This prevents Microsoft Visual C++ runtime DLLs such as
     *   `msvcp110.dll`, `vcamp110.dll`, and `vcomp110.dll` from being mistaken for PKCS#11
     *   middleware — they all contain the three-character substring `p11` as part of the
     *   version number `p110`.
     *
     * Known spy/debugging wrappers (e.g. `pkcs11-spy.so`) are excluded via [isSpyLibrary].
     */
    internal fun isPkcs11FileName(fileName: String): Boolean {
        if (isSpyLibrary(fileName)) return false
        val lower = fileName.lowercase()
        return PKCS11_NAME_PATTERNS.any { lower.contains(it) } ||
               P11_STANDALONE_PATTERN.containsMatchIn(lower)
    }

    /**
     * Return `true` when [fileName] (base name only) is a known PKCS#11 spy or debugging
     * wrapper library rather than actual middleware.
     *
     * The OpenSC project ships `pkcs11-spy.so` / `pkcs11-spy.dll` which is a logging
     * pass-through that requires the `PKCS11SPY` environment variable to point to the real
     * PKCS#11 module.  Loading it without that variable set produces errors or hangs.
     * Such libraries must never be probed or offered as signing tokens.
     */
    internal fun isSpyLibrary(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return SPY_LIBRARY_PATTERNS.any { lower.contains(it) }
    }

    /**
     * Derive a human-readable middleware display name from an absolute [libraryPath].
     * Falls back to the file's base name when no known vendor pattern matches.
     */
    internal fun deriveMiddlewareName(libraryPath: String): String {
        val lower = libraryPath.lowercase()
        return when {
            lower.contains("etpkcs11") || lower.contains("etoken") ||
                    lower.contains("/sac") || lower.contains("\\sac") -> "SafeNet eToken"
            lower.contains("gclib") || lower.contains("gemalto") ||
                    lower.contains("idprime") -> "Thales/Gemalto IDPrime"
            lower.contains("ykcs11") -> "YubiKey (YKCS11)"
            lower.contains("opensc") -> "OpenSC"
            lower.contains("iidp11") || lower.contains("netid") -> "SecMaker Net iD"
            lower.contains("cmp11") || lower.contains("charismathics") -> "Charismathics PKCS#11"
            lower.contains("softhsm") -> "SoftHSM2"
            lower.contains("libck") -> "Cryptoki Library"
            lower.contains("p11-kit-proxy") || lower.contains("p11kitproxy") -> "p11-kit Proxy"
            else -> libraryPath.substringAfterLast('/').substringAfterLast('\\')
        }
    }

	/**
	 * Return `true` when the given [path] refers to the p11-kit proxy PKCS#11 module.
	 *
	 * The p11-kit proxy is a PKCS#11 aggregator that exposes tokens from all registered
	 * underlying modules through a single library.  During deduplication, proxy results
	 * are processed after direct-library results so that the direct library's path takes
	 * precedence in the resulting [TokenInfo].  Proxy paths that report no identities
	 * are suppressed entirely — they add no information beyond what the direct libraries
	 * already provide.
	 */
	internal fun isProxyPath(path: String): Boolean {
		val lower = path.lowercase()
		return lower.contains("p11-kit-proxy") || lower.contains("p11kitproxy")
	}


    /**
     * Minimal JNA binding for `winscard.dll` (PC/SC).
     *
     * `jna-platform` does not ship a `Winscard` class in any released artifact, so we define
     * the small subset of the PC/SC API we need directly.  The interface uses the Unicode (`W`)
     * entry points; JNA maps `String` / `CharArray` parameters to wide-string types automatically.
     * `jna` and `jna-platform` 5.18.1 are declared explicitly in `build.gradle.kts` to override
     * the older transitive version pulled in by `dss-token`.
     */
    private interface WinscardLib : StdCallLibrary {
        fun sCardEstablishContext(dwScope: Int, pvReserved1: Any?, pvReserved2: Any?, phContext: IntByReference): Int
        fun sCardReleaseContext(hContext: Int): Int
        fun sCardListReadersW(
            hContext: Int,
            mszGroups: String?,
            mszReaders: CharArray?,
            pcchReaders: IntByReference,
        ): Int

        fun sCardConnectW(
            hContext: Int,
            szReader: String,
            dwShareMode: Int,
            dwPreferredProtocols: Int,
            phCard: IntByReference,
            pdwActiveProtocol: IntByReference,
        ): Int

        fun sCardDisconnect(hCard: Int, dwDisposition: Int): Int
        fun sCardStatusW(
            hCard: Int,
            mszReaderNames: CharArray?,
            pcchReaderLen: IntByReference?,
            pdwState: IntByReference?,
            pdwProtocol: IntByReference?,
            pbAtr: ByteArray?,
            pcbAtrLen: IntByReference?,
        ): Int

        companion object {
            const val SCARD_S_SUCCESS = 0
            const val SCARD_SCOPE_SYSTEM = 2
            const val SCARD_SHARE_SHARED = 2
            const val SCARD_PROTOCOL_T0 = 1
            const val SCARD_PROTOCOL_T1 = 2
            const val SCARD_LEAVE_CARD = 0

            val INSTANCE: WinscardLib? by lazy {
                runCatching {
                    Native.load("winscard", WinscardLib::class.java) as WinscardLib
                }.getOrNull()
            }
        }
    }

    /**
     * List PC/SC smart card readers via `SCardListReaders` and resolve the PKCS#11 middleware
     * for each inserted card from
     * `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`.
     *
     * Returns an empty list when no smart card service is running, no readers are connected,
     * or `winscard.dll` is unavailable.
     */
    private fun discoverViaPcsc(): List<Pair<String, String>> {
        val api = WinscardLib.INSTANCE ?: return emptyList()
        val results = mutableListOf<Pair<String, String>>()

        runCatching {
            val ctxRef = IntByReference()
            if (api.sCardEstablishContext(WinscardLib.SCARD_SCOPE_SYSTEM, null, null, ctxRef)
                != WinscardLib.SCARD_S_SUCCESS
            ) return emptyList()
            val ctx = ctxRef.value

            try {
                val lenRef = IntByReference()
                if (api.sCardListReadersW(ctx, null, null, lenRef) != WinscardLib.SCARD_S_SUCCESS)
                    return emptyList()

                val buf = CharArray(lenRef.value)
                if (api.sCardListReadersW(ctx, null, buf, lenRef) != WinscardLib.SCARD_S_SUCCESS)
                    return emptyList()

                for (reader in String(buf).split("\u0000").filter { it.isNotEmpty() }) {
                    runCatching {
                        val cardRef = IntByReference()
                        val protoRef = IntByReference()
                        if (api.sCardConnectW(
                                ctx, reader,
                                WinscardLib.SCARD_SHARE_SHARED,
                                WinscardLib.SCARD_PROTOCOL_T0 or WinscardLib.SCARD_PROTOCOL_T1,
                                cardRef, protoRef,
                            ) != WinscardLib.SCARD_S_SUCCESS
                        ) return@runCatching
                        val card = cardRef.value

                        try {
                            val atrBuf = ByteArray(ATR_MAX_SIZE_BYTES)
                            val atrLen = IntByReference(atrBuf.size)
                            api.sCardStatusW(card, null, null, null, null, atrBuf, atrLen)
                            val atrHex = atrBuf.take(atrLen.value)
                                .joinToString("") { "%02X".format(it) }
                            resolveFromAtr(atrHex)
                                ?.takeIf { File(it).exists() }
                                ?.let { results += deriveMiddlewareName(it) to it }
                        } finally {
                            api.sCardDisconnect(card, WinscardLib.SCARD_LEAVE_CARD)
                        }
                    }
                }
            } finally {
                api.sCardReleaseContext(ctx)
            }
        }
        return results
    }

    /**
     * Look up the PKCS#11 library for a card by its ATR hex string in
     * `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`.
     *
     * Prefers the subkey whose stored `ATR` exactly matches [atrHex]; falls back to the
     * first subkey with an existing `Pkcs11Lib` path when no exact match is found.
     * Returns null when the hive is inaccessible or no entry is found.
     */
    private fun resolveFromAtr(atrHex: String): String? {
        val root = "SOFTWARE\\Microsoft\\Cryptography\\Calais\\SmartCards"
        runCatching {
            val subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, root)
            var fallback: String? = null

            for (subKey in subKeys) {
                runCatching {
                    val values = Advapi32Util.registryGetValues(
                        WinReg.HKEY_LOCAL_MACHINE, "$root\\$subKey"
                    )
                    val pkcs11 = (values["Pkcs11Lib"] ?: values["Crypto Provider"]) as? String
                        ?: return@runCatching
                    if (fallback == null && File(pkcs11).exists()) fallback = pkcs11
                    val atr = values["ATR"] as? String ?: return@runCatching
                    if (atr.replace(" ", "").equals(atrHex, ignoreCase = true)) return pkcs11
                }
            }
            return fallback
        }
        return null
    }

    /**
     * Detect the macOS CryptoTokenKit PKCS#11 shim (`/usr/lib/libctkpcscd.dylib`) when a
     * smart card or CTK token extension is actually present.
     *
     * Uses `security list-smartcards` and `pluginkit -mAT com.apple.ctk.token`.
     * The shim is only returned when at least one card or CTK extension is found, avoiding a
     * spurious PKCS#11 slot on systems with no tokens.  Other middleware paths (OpenSC, etc.)
     * are covered by [candidatesForOs] and [discoverViaP11Kit].
     *
     * Returns an empty list when neither tool is available or no tokens are present.
     */
    private fun discoverViaMacOsSecurity(): List<Pair<String, String>> {
        val shimPath = "/usr/lib/libctkpcscd.dylib"
        if (!File(shimPath).exists()) return emptyList()

        val hasCard = runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("security", "list-smartcards"))
                .inputStream.bufferedReader().readText().isNotBlank()
        }.getOrDefault(false)

        if (hasCard) return listOf("macOS Smart Card (PC/SC)" to shimPath)

        val ctkExtensions = runCatching {
            val output = Runtime.getRuntime()
                .exec(arrayOf("pluginkit", "-mAT", "com.apple.ctk.token"))
                .inputStream.bufferedReader().readText()
            Regex("""^\s*\+\s*(\S+)""", RegexOption.MULTILINE)
                .findAll(output).map { it.groupValues[1] }.toList()
        }.getOrDefault(emptyList())

        if (ctkExtensions.isEmpty()) return emptyList()
        return listOf("macOS CryptoTokenKit (${ctkExtensions.size} extension(s))" to shimPath)
    }

    /**
     * Scan well-known vendor registry trees for PKCS#11 library paths via `reg query`.
     *
     * Covers `HKLM\SOFTWARE\SafeNet`, `Gemalto`, `HID Global`, `Charismathics`, `SecMaker`.
     * `Calais\SmartCards` is excluded — it is already read by [discoverViaPcsc] via JNA.
     *
     * Returns an empty list when the registry is inaccessible or `reg` is not on the PATH.
     */
    private fun discoverViaWindowsRegistry(jvmIs64Bit: Boolean): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val arch = if (jvmIs64Bit) "/reg:64" else "/reg:32"

        val roots = listOf(
            "HKLM\\SOFTWARE\\SafeNet",
            "HKLM\\SOFTWARE\\Gemalto",
            "HKLM\\SOFTWARE\\HID Global",
            "HKLM\\SOFTWARE\\Charismathics",
            "HKLM\\SOFTWARE\\SecMaker",
        )

        for (root in roots) {
            runCatching {
                val output = Runtime.getRuntime()
                    .exec(arrayOf("reg", "query", root, "/s", arch))
                    .inputStream.bufferedReader().readText()

                val pkcs11Pattern = Regex(
                    """(?i)(PKCS11Lib|Pkcs11|pkcs11|p11|eTPKCS11|gclib|opensc-pkcs11|iidp11|cmP11)\s+REG_SZ\s+(.+\.dll)"""
                )
                for (match in pkcs11Pattern.findAll(output)) {
                    val path = match.groupValues[2].trim()
                    if (File(path).exists()) results += deriveMiddlewareName(path) to path
                }

                val dllPattern = Regex("""REG_SZ\s+(C:\\[^\r\n]*\.dll)""", RegexOption.IGNORE_CASE)
                for (match in dllPattern.findAll(output)) {
                    val path = match.groupValues[1].trim()
                    if (isPkcs11FileName(File(path).name) && File(path).exists())
                        results += deriveMiddlewareName(path) to path
                }
            }
        }

        return results
    }

    /**
     * Scan `%SystemRoot%\System32` (64-bit JVM) or `%SystemRoot%\SysWOW64` (32-bit JVM) for
     * files whose names match [isPkcs11FileName].
     */
    private fun discoverViaDirScan(jvmIs64Bit: Boolean): List<Pair<String, String>> {
        val sysRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val dir = File(if (jvmIs64Bit) "$sysRoot\\System32" else "$sysRoot\\SysWOW64")
        return dir.listFiles { f -> f.isFile && isPkcs11FileName(f.name) }
            ?.map { f -> deriveMiddlewareName(f.absolutePath) to f.absolutePath }
            ?: emptyList()
    }

    /**
     * Load the p11-kit proxy module if present on the system.
     *
     * The proxy is a single PKCS#11 library that aggregates every module registered with
     * p11-kit, so loading it exposes all system-registered tokens through one entry point.
     * Only the first existing path from [P11_KIT_PROXY_PATHS] is returned — multiple proxy
     * installations on the same machine are uncommon and the serial-number deduplication
     * in [discoverTokens] would collapse them anyway.
     *
     * Returns an empty list when the proxy library is not found.
     *
     * @param proxyPaths Ordered list of candidate proxy paths; override for testing.
     */
    internal fun discoverViaP11KitProxy(
        proxyPaths: List<String> = P11_KIT_PROXY_PATHS,
    ): List<Pair<String, String>> =
        proxyPaths.firstOrNull { File(it).exists() }
            ?.let { listOf("p11-kit Proxy" to it) }
            ?: emptyList()

    /**
     * Scan a list of native library directories for files whose names pass [isPkcs11FileName].
     *
     * This catches middleware installed to standard OS library paths without a p11-kit
     * `.module` registration file — for example, SafeNet Authentication Client on Linux
     * (`libeTPkcs11.so`) or YubiKey YKCS11 (`libykcs11.so`).
     *
     * Directories that do not exist are silently skipped.
     *
     * @param dirs Absolute directory paths to scan; ordered from highest to lowest priority.
     */
    internal fun discoverViaLibDirs(
        dirs: List<String>,
    ): List<Pair<String, String>> = dirs.flatMap { dirPath ->
        File(dirPath).listFiles { f -> f.isFile && isPkcs11FileName(f.name) }
            ?.map { f -> deriveMiddlewareName(f.absolutePath) to f.absolutePath }
            ?: emptyList()
    }

    /**
     * Parse p11-kit `*.module` files from standard search paths.     *
     * Each file is a simple `key: value` format.  Reads `module:` (library path) and
     * optionally `name:` / `description:` for the display name.
     *
     * Search paths:
     * - `/etc/pkcs11/modules`
     * - `/usr/share/p11-kit/modules`
     * - `~/.config/pkcs11/modules`
     * - `/Library/Application Support/p11-kit/modules` (macOS)
     * - Directory of `$P11_KIT_CONFIG_FILE` (if set)
     */
    private fun discoverViaP11Kit(): List<Pair<String, String>> {
        val searchDirs = buildList {
            add("/etc/pkcs11/modules")
            add("/usr/share/p11-kit/modules")
            add("${System.getProperty("user.home")}/.config/pkcs11/modules")
            add("/Library/Application Support/p11-kit/modules")
            val env = System.getenv("P11_KIT_CONFIG_FILE")
            if (env != null) add(File(env).parent)
        }

        val results = mutableListOf<Pair<String, String>>()

        for (dirPath in searchDirs) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            dir.listFiles { f -> f.isFile && f.name.endsWith(".module") }?.forEach { moduleFile ->
                runCatching {
                    val props = Properties()
                    moduleFile.bufferedReader().use { reader ->
                        reader.lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith('#') && it.contains(':') }
                            .forEach { line ->
                                val colon = line.indexOf(':')
                                props[line.substring(0, colon).trim()] =
                                    line.substring(colon + 1).trim()
                            }
                    }
                    val libPath = props.getProperty("module") ?: return@runCatching
                    if (!File(libPath).exists()) return@runCatching
                    val name = props.getProperty("name")
                        ?: props.getProperty("description")
                        ?: deriveMiddlewareName(libPath)
                    results += name to libPath
                }
            }
        }

        return results
    }

    private companion object {
        val logger = KotlinLogging.logger {}


        /**
         * Maximum ATR length defined by ISO/IEC 7816-3: 32 bytes of ATR + 1 TCK byte.
         */
        const val ATR_MAX_SIZE_BYTES = 33

        /**
         * Substring patterns that unambiguously identify PKCS#11 middleware filenames.
         * The standalone `p11` token is deliberately absent; it is matched separately by
         * [P11_STANDALONE_PATTERN] to avoid false positives from VC++ runtime version numbers.
         */
        val PKCS11_NAME_PATTERNS = listOf(
            "pkcs11", "etpkcs", "gclib", "opensc", "iidp11", "cmp11",
            "softhsm", "libsac", "libck", "cryptoki", "ykcs11",
        )

        /**
         * Substring patterns that identify PKCS#11 spy or debugging wrapper libraries.
         *
         * These libraries (e.g., OpenSC `pkcs11-spy.so`) are logging pass-throughs that
         * require additional environment configuration (`PKCS11SPY`) to function.  Loading
         * them without that configuration causes errors or hangs.
         */
        val SPY_LIBRARY_PATTERNS = listOf("pkcs11-spy", "pkcs11spy", "p11-spy", "p11spy")

        /**
         * Matches the standalone `p11` token in a lowercase filename when it is **not**
         * immediately followed by a digit.  Examples that match: `libp11.so`, `p11-kit.so`,
         * `p11.dll`.  Examples that do **not** match: `msvcp110.dll`, `vcamp110.dll`.
         */
        val P11_STANDALONE_PATTERN = Regex("""p11(?!\d)""")

        /**
         * Ordered candidate paths for the p11-kit proxy PKCS#11 module.
         *
         * The proxy aggregates all modules registered with p11-kit and exposes their slots
         * through a single library entry point.  Paths cover the multiarch layouts used by
         * Debian/Ubuntu, RPM-based distributions, and manual installations.
         */
        val P11_KIT_PROXY_PATHS = listOf(
            "/usr/lib/x86_64-linux-gnu/pkcs11/p11-kit-proxy.so",
            "/usr/lib/aarch64-linux-gnu/pkcs11/p11-kit-proxy.so",
            "/usr/lib64/pkcs11/p11-kit-proxy.so",
            "/usr/lib/pkcs11/p11-kit-proxy.so",
            "/usr/local/lib/pkcs11/p11-kit-proxy.so",
        )
    }
}

/**
 * Resolve the classpath to use when spawning a [Pkcs11ProbeWorker] subprocess via `java`.
 *
 * Attempts the following strategies in order:
 * 1. **`java.class.path` system property** — always available when the app is launched via
 *    `java -cp` or from an IDE, and usually set by jpackage native launchers.
 * 2. **Code-source JAR directory scan** — when `java.class.path` is null or blank (observed
 *    in some jpackage distributions), the JAR containing [Pkcs11ProbeWorker] is located via
 *    [Class.getProtectionDomain], and every `*.jar` in its parent directory is included.
 *    In a jpackage image this corresponds to all JARs under the `lib/app` directory, which
 *    is the exact set the native launcher would have placed on the classpath.
 *
 * @return The resolved classpath string, or `null` when neither strategy yields a usable path.
 */
internal fun resolveProbeClasspath(): String? {
    val logger = KotlinLogging.logger {}

    val sysCp = System.getProperty("java.class.path")
    if (!sysCp.isNullOrBlank()) {
        logger.debug { "Probe classpath resolved from java.class.path (${sysCp.length} chars)" }
        return sysCp
    }

    logger.info { "java.class.path is null or blank — falling back to code-source JAR directory scan" }

    val codeSource = Pkcs11ProbeWorker::class.java.protectionDomain?.codeSource?.location
    if (codeSource == null) {
        logger.warn { "Cannot resolve code source for Pkcs11ProbeWorker — subprocess probing will be unavailable" }
        return null
    }

    val sourceFile = runCatching { File(codeSource.toURI()) }.getOrElse { e ->
        logger.warn(e) { "Cannot convert code source URI to file path: $codeSource" }
        return null
    }

    val appDir = sourceFile.parentFile
    if (appDir == null || !appDir.isDirectory) {
        logger.warn { "Code source parent directory does not exist: ${sourceFile.parent}" }
        return null
    }

    val jars = appDir.listFiles { f -> f.isFile && f.extension == "jar" }
    if (jars.isNullOrEmpty()) {
        logger.warn { "No JAR files found in code source directory: ${appDir.absolutePath}" }
        return null
    }

    val classpath = jars.joinToString(File.pathSeparator) { it.absolutePath }
    logger.info { "Probe classpath resolved from ${jars.size} JARs in ${appDir.absolutePath}" }
    return classpath
}

/**
 * Build the command line for a [Pkcs11ProbeWorker] subprocess.
 *
 * Attempts two strategies in order:
 * 1. **`java` binary** — the standard `java` executable inside `java.home/bin/`.  Works from
 *    an IDE, `java -jar`, or any standard JVM launch.  Requires [resolveProbeClasspath] to
 *    succeed.
 * 2. **Native launcher** — the application's own executable as reported by
 *    [ProcessHandle.current].  In a jpackage distribution the `java` binary is stripped by
 *    `--strip-native-commands`, but the native launcher (e.g. `/opt/omnisign/bin/OmniSign`)
 *    is always present.  The subprocess is started with the `probe` argument so that the
 *    application's `main()` delegates directly to [Pkcs11ProbeWorker] and exits without
 *    starting the full UI or DI framework.
 *
 * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
 * @return The full command list ready for [ProcessBuilder], or `null` when no usable
 *   executable can be found.
 */
internal fun resolveProbeCommand(libraryPath: String): List<String>? {
    val logger = KotlinLogging.logger {}

    val javaBinaryName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    val javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaBinaryName).toString()
    if (File(javaExecutable).exists()) {
        val classpath = resolveProbeClasspath()
        if (classpath == null) {
            logger.warn { "java binary found but classpath resolution failed — cannot probe '$libraryPath'" }
            return null
        }
        return buildList {
            add(javaExecutable)
            add("--enable-native-access=ALL-UNNAMED")
            System.getProperty("omnisign.crash.dir")?.let { crashDir ->
                add("-XX:ErrorFile=$crashDir/hs_err_pid%p.log")
            }
            addAll(listOf("-cp", classpath, Pkcs11ProbeWorker::class.java.name, libraryPath))
        }
    }

    logger.info { "java binary not found at '$javaExecutable' — trying native launcher fallback" }

    val nativeLauncher = ProcessHandle.current().info().command().orElse(null)
    if (nativeLauncher != null && File(nativeLauncher).exists()) {
        logger.info { "Using native launcher for PKCS#11 probe: $nativeLauncher" }
        return listOf(nativeLauncher, "probe", libraryPath)
    }

    logger.warn { "Neither java binary nor native launcher found — cannot spawn probe for '$libraryPath'" }
    return null
}

/**
 * Probe a PKCS#11 library for token identities in an isolated subprocess.
 *
 * Spawns a child process running [Pkcs11ProbeWorker] to probe the given library.
 * If the native library causes a fatal crash (e.g., SIGSEGV from SafeNet eToken's
 * `libeTPKCS15.so` when no card is inserted), only the child process is terminated — the
 * host JVM continues normally.
 *
 * The subprocess command is resolved via [resolveProbeCommand], which handles two
 * environments:
 * - **Standard JVM** (IDE, `java -jar`): spawns `java -cp ... Pkcs11ProbeWorker`.
 * - **jpackage distribution**: the bundled runtime has no `java` binary (stripped by
 *   `--strip-native-commands`), so the application's own native launcher is invoked with
 *   the `probe` argument instead.
 *
 * Error handling uses two mechanisms:
 * - **Crash detection**: probes that crash (SIGSEGV, SIGABRT, etc.) exit immediately with
 *   a non-zero code.  [Process.waitFor] returns as soon as the process terminates, so
 *   crashed probes are handled in milliseconds regardless of the configured timeout.
 * - **Hang safety net**: the [timeoutSeconds] parameter guards against middleware that
 *   freezes without crashing.  Only truly unresponsive probes wait the full duration
 *   before being forcibly killed.
 *
 * Falls back to an empty list when:
 * - The subprocess times out (killed after [timeoutSeconds]).
 * - The subprocess exits with a non-zero code (native crash or probing error).
 * - The subprocess output cannot be parsed.
 * - No suitable executable can be resolved.
 *
 * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
 * @param timeoutSeconds Maximum wall-clock time to wait for the subprocess before killing it.
 *   Only reached when the process hangs; crashed probes are handled immediately.
 */
internal fun probeTokenIdentitiesViaSubprocess(
    libraryPath: String,
    timeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
): List<Pkcs11TokenIdentity> {
    val logger = KotlinLogging.logger {}
    return runCatching {
        when (val result = runProbeSubprocess(libraryPath, timeoutSeconds)) {
            null -> {
                logger.warn { "Cannot resolve probe command — skipping probe for '$libraryPath'" }
                emptyList()
            }

            is Pkcs11SubprocessResult.TimedOut -> {
                logger.warn {
                    "PKCS#11 probe subprocess pid=${result.pid} for '$libraryPath' timed out after ${timeoutSeconds}s"
                }
                emptyList()
            }

            is Pkcs11SubprocessResult.Crashed -> {
                val signal = if (result.exitCode > 128) " (${signalName(result.exitCode - 128)})" else ""
                logger.warn {
                    buildString {
                        append("PKCS#11 probe subprocess pid=${result.pid} for '$libraryPath' exited with code ${result.exitCode}$signal")
                        if (result.stderr.isNotEmpty()) {
                            append("\n  stderr: ${result.stderr}")
                        }
                        if (result.exitCode - 128 == 11 || result.exitCode - 128 == 6) {
                            append("\n  Check for hs_err_pid${result.pid}.log in the crash directory")
                        }
                    }
                }
                emptyList()
            }

            is Pkcs11SubprocessResult.Success -> {
                result.stdout.lines()
                    .filter { it.contains('\t') }
                    .map { line ->
                        val (label, serial) = line.split('\t', limit = 2)
                        Pkcs11TokenIdentity(label = label, serialNumber = serial, libraryPath = libraryPath)
                    }
            }
        }
    }.getOrElse { e ->
        logger.warn(e) { "Failed to spawn PKCS#11 probe subprocess for '$libraryPath'" }
        emptyList()
    }
}

/**
 * Probe a PKCS#11 [libraryPath] for the identities of all currently inserted tokens.
 *
 * Uses JNA to call `C_Initialize`, `C_GetSlotList(tokenPresent=CK_TRUE)`, and
 * `C_GetTokenInfo` to read the hardware token label and serial number from each
 * occupied slot.  This never calls `C_Login` and therefore never risks incrementing
 * a wrong-PIN counter.
 *
 * `C_Initialize` is called idempotently; `CKR_CRYPTOKI_ALREADY_INITIALIZED` is treated
 * as success.  `C_Finalize` is deliberately NOT called so existing sessions created by
 * DSS or the SunPKCS11 provider are not interrupted.
 *
 * **Intentional code duplication**: the slot-enumeration logic
 * (`C_GetSlotList` → `C_GetTokenInfo` → build [Pkcs11TokenIdentity] list) is
 * near-identical to [Pkcs11SessionManager.probeInProcess].  The duplication is deliberate:
 * this function runs inside an isolated [Pkcs11ProbeWorker] subprocess where native crashes
 * (SIGSEGV, SIGABRT) are contained, whereas [Pkcs11SessionManager.probeInProcess] operates
 * on a pre-initialized in-process session.  Merging them would couple the crash-isolated
 * subprocess path to the in-process session lifecycle, defeating the isolation boundary.
 *
 * Returns an empty list when the library cannot be loaded, no slots have tokens, or
 * any PKCS#11 call fails.
 */
@Suppress("DuplicatedCode")
internal fun probeTokenIdentities(libraryPath: String): List<Pkcs11TokenIdentity> = runCatching {
    @Suppress("UNCHECKED_CAST")
    val lib = Native.load(libraryPath, Pkcs11ProbeLib::class.java) as Pkcs11ProbeLib
    val initRv = lib.C_Initialize(null).toLong()
    if (initRv != CKR_OK && initRv != CKR_CRYPTOKI_ALREADY_INITIALIZED) return emptyList()

    val countMem = Memory(Native.LONG_SIZE.toLong()).also { it.clear() }
    if (lib.C_GetSlotList(1.toByte(), null, countMem).toLong() != CKR_OK) return emptyList()
    val slotCount = countMem.getNativeLong(0).toLong().toInt()
    if (slotCount <= 0) return emptyList()

    val slotsMem = Memory((slotCount.toLong() * Native.LONG_SIZE))
    slotsMem.clear()
    countMem.setNativeLong(0, NativeLong(slotCount.toLong()))
    if (lib.C_GetSlotList(1.toByte(), slotsMem, countMem).toLong() != CKR_OK) return emptyList()

    val results = mutableListOf<Pkcs11TokenIdentity>()
    for (i in 0 until slotCount) {
        val slotId = slotsMem.getNativeLong((i.toLong() * Native.LONG_SIZE))
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
    results
}.getOrDefault(emptyList())




/**
 * Normalize a PKCS#11 token serial number for deduplication comparison.
 *
 * Different middleware implementations may report the same physical serial with
 * different padding and casing — for example, SafeNet uses null-byte padding while
 * OpenSC uses space-padding, and some middleware upper-cases the hex serial while
 * others preserve the case from the card.  This function strips all whitespace and
 * null bytes and upper-cases the result so that `"ABC 123\u0000"` and `"abc123  "`
 * both normalize to `"ABC123"`.
 *
 * @param serial The raw serial string (already decoded from bytes, may contain
 *   residual whitespace or null-byte artifacts).
 * @return The normalized serial suitable for set-based deduplication.
 */
internal fun normalizeSerial(serial: String): String =
	serial.filterNot { it.isWhitespace() || it == '\u0000' }.uppercase()

/**
 * Map a POSIX signal number to its conventional name for diagnostic logging.
 *
 * @param signal Signal number (e.g. 6 for SIGABRT, 11 for SIGSEGV).
 * @return Human-readable name such as `"SIGSEGV"`, or `"signal $signal"` for unmapped values.
 */
internal fun signalName(signal: Int): String = when (signal) {
    1 -> "SIGHUP"
    2 -> "SIGINT"
    3 -> "SIGQUIT"
    4 -> "SIGILL"
    6 -> "SIGABRT"
    7 -> "SIGBUS"
    8 -> "SIGFPE"
    9 -> "SIGKILL"
    11 -> "SIGSEGV"
    13 -> "SIGPIPE"
    14 -> "SIGALRM"
    15 -> "SIGTERM"
    else -> "signal $signal"
}
