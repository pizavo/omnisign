package cz.pizavo.omnisign.data.service

import com.sun.jna.*
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.P11_KIT_PROXY_PATHS
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.P11_STANDALONE_PATTERN
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.PKCS11_NAME_PATTERNS
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit

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
 * Discovers PKCS#11 middleware libraries available on the current system and resolves
 * them to physical token identities.
 *
 * Discovery is layered from most to least authoritative:
 * 1. OS-native sources ([discoverViaOs]) — PC/SC on Windows, `security`/`pluginkit` on macOS,
 *    p11-kit on Linux.
 * 2. Curated fallback list ([candidatesForOs]) — well-known vendor paths not covered by
 *    OS-native discovery.
 * 3. App-data drop directory — any PKCS#11-named file placed under
 *    `<appDataDir>/omnisign/pkcs11/`.
 * 4. User-supplied paths — entries from
 *    [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries].
 *
 * Duplicates are resolved first by canonical path, then by probing the actual hardware
 * token identity (label and serial number) via `C_GetTokenInfo`.  Multiple middleware DLLs
 * that report the same physical token serial produce a single [TokenInfo].
 *
 * @property tokenProber Strategy for probing PKCS#11 libraries for hardware token identities.
 *   Defaults to subprocess-based probing via [probeTokenIdentitiesViaSubprocess] to isolate
 *   native crashes (SIGSEGV) from the host JVM; override for testing.
 * @property probeTimeoutSeconds Maximum time in seconds to wait for a single PKCS#11 library
 *   probe subprocess before killing it.  Defaults to [DEFAULT_PROBE_TIMEOUT_SECONDS].
 */
class Pkcs11Discoverer(
    private val tokenProber: (String) -> List<Pkcs11TokenIdentity> = ::probeTokenIdentitiesViaSubprocess,
    private val probeTimeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
) {

    /**
     * Holds the raw output of probing a single PKCS#11 library.
     *
     * Used internally by [discoverTokens] to collect parallel probe results before
     * performing serial deduplication.
     */
    private data class LibProbeResult(
        val name: String,
        val path: String,
        val identities: List<Pkcs11TokenIdentity>,
    )

    /**
     * Discover all PKCS#11 tokens available on the system.
     *
     * Probing runs in parallel on [Dispatchers.IO] — one coroutine per unique library path —
     * so that slow or unresponsive middleware does not delay discovery of other tokens.
     * Deduplication (by serial number or middleware family) is applied after all probes finish
     * and therefore produces deterministic results regardless of completion order.
     *
     * @param appDataPkcs11Dir Optional drop directory; every PKCS#11-named file found here is
     *   added to the candidate list without any config change.
     * @param userPkcs11Libraries Additional `(display name, path)` pairs supplied by the user.
     *   Only entries whose file exists on disk are included.
     */
    suspend fun discoverTokens(
        appDataPkcs11Dir: File? = null,
        userPkcs11Libraries: List<Pair<String, String>> = emptyList(),
    ): List<TokenInfo> {
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
        merge(candidatesForOs(os, jvmIs64Bit).filter { (_, path) -> File(path).exists() })
        if (appDataPkcs11Dir != null && appDataPkcs11Dir.isDirectory) {
            merge(
                appDataPkcs11Dir
                    .listFiles { f -> f.isFile && isPkcs11FileName(f.name) }
                    ?.map { f -> deriveMiddlewareName(f.absolutePath) to f.absolutePath }
                    ?: emptyList()
            )
        }
        merge(userPkcs11Libraries.filter { (_, path) -> File(path).exists() })

        val candidates = seen.values.filterNot { (_, path) -> isSpyLibrary(File(path).name) }

        val probeResults = coroutineScope {
            candidates.map { (name, path) ->
                async(Dispatchers.IO) {
                    LibProbeResult(name, path, tokenProber(path))
                }
            }.awaitAll()
        }

        val result = mutableListOf<TokenInfo>()
        val seenSerials = mutableSetOf<String>()
        val seenFamilies = mutableSetOf<String>()

        for ((name, path, identities) in probeResults) {
            if (identities.isNotEmpty()) {
                for (identity in identities) {
                    if (seenSerials.add(identity.serialNumber)) {
                        result += TokenInfo(
                            id = "pkcs11-${identity.serialNumber}",
                            name = identity.label,
                            type = TokenType.PKCS11,
                            path = path,
                            requiresPin = true,
                        )
                    }
                }
            } else {
                val family = deriveMiddlewareFamily(path)
                if (seenFamilies.add(family)) {
                    result += TokenInfo(
                        id = "pkcs11-${File(path).name}",
                        name = name,
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
     * Return `(display name, absolute path)` pairs for the curated fallback list of PKCS#11
     * middleware candidates appropriate for the given [os] string (lowercase).
     *
     * **Scope**: only paths not reachable by [discoverViaOs]:
     * - Windows: `Program Files` vendor paths not always registered in the registry, and
     *   standalone tools (SoftHSM2) that never register.  `System32`/`SysWOW64` is already
     *   covered by the dir scan.  Specifically: SafeNet SAC 10.x does not always copy its DLL
     *   to `System32`; OpenSC only does so when "Register in system" is checked at installation time;
     *   SoftHSM2 for Windows is a standalone zip.
     * - Linux/macOS: libraries from installations without a p11-kit `.module` file.
     *
     * Stale entries (file no longer on disk) are silently dropped by the [File.exists] filter
     * in [discoverTokens].  Only the bitness-appropriate path is included.
     */
    internal fun candidatesForOs(
        os: String,
        jvmIs64Bit: Boolean = System.getProperty("sun.arch.data.model") == "64",
    ): List<Pair<String, String>> = when {
        os.contains("win") && jvmIs64Bit -> listOf(
            "SafeNet Authentication Client" to
                    "C:\\Program Files\\SafeNet\\Authentication\\SAC\\x64\\eTPKCS11.dll",
            "OpenSC" to
                    "C:\\Program Files\\OpenSC Project\\OpenSC\\pkcs11\\opensc-pkcs11.dll",
            "SoftHSM2" to "C:\\SoftHSM2\\lib\\softhsm2-x64.dll",
        )

        os.contains("win") -> listOf(
            "SafeNet Authentication Client" to
                    "C:\\Program Files (x86)\\SafeNet\\Authentication\\SAC\\x32\\eTPKCS11.dll",
            "OpenSC" to
                    "C:\\Program Files (x86)\\OpenSC Project\\OpenSC\\pkcs11\\opensc-pkcs11.dll",
        )

        os.contains("mac") -> listOf(
            "SafeNet Authentication Client" to "/usr/local/lib/libeTPkcs11.dylib",
            "YubiKey (YKCS11)" to "/usr/local/lib/libykcs11.dylib",
            "OpenSC" to "/Library/OpenSC/lib/opensc-pkcs11.so",
            "OpenSC (Homebrew)" to "/opt/homebrew/lib/opensc-pkcs11.so",
            "macOS Smart Card" to "/usr/lib/libctkpcscd.dylib",
            "SoftHSM2 (Homebrew)" to "/opt/homebrew/lib/softhsm/libsofthsm2.so",
        )

        jvmIs64Bit -> listOf(
            "SafeNet Authentication Client" to "/usr/lib/libeTPkcs11.so",
            "SafeNet Authentication Client (lib64)" to "/usr/lib64/libeTPkcs11.so",
            "SafeNet Authentication Client (local)" to "/usr/local/lib/libeTPkcs11.so",
            "YubiKey (YKCS11)" to "/usr/lib/libykcs11.so",
            "YubiKey (YKCS11, lib64)" to "/usr/lib64/libykcs11.so",
            "OpenSC" to "/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so",
            "OpenSC (aarch64)" to "/usr/lib/aarch64-linux-gnu/opensc-pkcs11.so",
            "OpenSC (local)" to "/usr/local/lib/opensc-pkcs11.so",
            "SoftHSM2" to "/usr/lib/softhsm/libsofthsm2.so",
            "SoftHSM2 (lib64)" to "/usr/lib64/softhsm/libsofthsm2.so",
        )

        else -> listOf(
            "SafeNet Authentication Client" to "/usr/lib/libeTPkcs11.so",
            "SafeNet Authentication Client (local)" to "/usr/local/lib/libeTPkcs11.so",
            "YubiKey (YKCS11)" to "/usr/lib/libykcs11.so",
            "OpenSC" to "/usr/lib/opensc-pkcs11.so",
            "OpenSC (local)" to "/usr/local/lib/opensc-pkcs11.so",
            "SoftHSM2" to "/usr/lib/softhsm/libsofthsm2.so",
        )
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
     * Derive a middleware family identifier from a library [path].
     *
     * Libraries within the same family access the same physical token slots and should be
     * deduplicated when hardware probing is unavailable.  For example, SafeNet Authentication
     * Client ships both `eTPKCS11.dll` and `gclib.dll` — both talk to the same smart card,
     * so they belong to family `"safenet"`.
     *
     * Unknown libraries fall back to their canonical path, so they are never grouped with
     * unrelated entries.
     */
    internal fun deriveMiddlewareFamily(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.contains("etpkcs11") || lower.contains("etoken") ||
                    lower.contains("/sac") || lower.contains("\\sac") ||
                    lower.contains("gclib") || lower.contains("gemalto") ||
                    lower.contains("idprime") -> "safenet"

            lower.contains("ykcs11") -> "yubikey"
            lower.contains("opensc") -> "opensc"
            lower.contains("iidp11") || lower.contains("netid") -> "secmaker"
            lower.contains("cmp11") || lower.contains("charismathics") -> "charismathics"
            lower.contains("softhsm") -> "softhsm"
            lower.contains("libck") || lower.contains("cryptoki") -> "cryptoki"
            else -> runCatching { File(path).canonicalPath }.getOrElse { path }
        }
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
         * Default timeout (in seconds) for probing a single PKCS#11 library.
         *
         * Generous enough for slow smart card readers but prevents unresponsive
         * middleware from blocking discovery indefinitely.
         */
        const val DEFAULT_PROBE_TIMEOUT_SECONDS = 10L

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
 * Probe a PKCS#11 library for token identities in an isolated subprocess.
 *
 * Spawns a child JVM running [Pkcs11ProbeWorker] with the same classpath as the current
 * process.  If the native library causes a fatal crash (e.g. SIGSEGV from SafeNet eToken's
 * `libeTPKCS15.so` when no card is inserted), only the child process is terminated — the
 * host JVM continues normally.
 *
 * Falls back to an empty list when:
 * - The subprocess times out (killed after [timeoutSeconds]).
 * - The subprocess exits with a non-zero code (native crash or probing error).
 * - The subprocess output cannot be parsed.
 * - The current JVM's classpath cannot be resolved.
 *
 * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
 * @param timeoutSeconds Maximum wall-clock time to wait for the subprocess before killing it.
 */
internal fun probeTokenIdentitiesViaSubprocess(
    libraryPath: String,
    timeoutSeconds: Long = 10,
): List<Pkcs11TokenIdentity> {
    val logger = KotlinLogging.logger {}
    return runCatching {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path") ?: return emptyList()

        val process = ProcessBuilder(
            javaExecutable,
            "--enable-native-access=ALL-UNNAMED",
            "-cp", classpath,
            Pkcs11ProbeWorker::class.java.name,
            libraryPath,
        )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            logger.warn { "PKCS#11 probe subprocess for '$libraryPath' timed out after ${timeoutSeconds}s — killing" }
            process.destroyForcibly()
            return emptyList()
        }
        if (process.exitValue() != 0) {
            logger.debug { "PKCS#11 probe subprocess for '$libraryPath' exited with code ${process.exitValue()}" }
            return emptyList()
        }

        output.lines()
            .filter { it.contains('\t') }
            .map { line ->
                val (label, serial) = line.split('\t', limit = 2)
                Pkcs11TokenIdentity(label = label, serialNumber = serial, libraryPath = libraryPath)
            }
    }.getOrElse { e ->
        logger.debug(e) { "Failed to spawn PKCS#11 probe subprocess for '$libraryPath'" }
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
 * Returns an empty list when the library cannot be loaded, no slots have tokens, or
 * any PKCS#11 call fails.
 */
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
}.getOrDefault(emptyList())

/**
 * JNA binding for the PKCS#11 functions needed to probe token identities.
 * Uses cdecl convention as mandated by the PKCS#11 v2.20 spec.
 */
private interface Pkcs11ProbeLib : Library {
    /**
     * Initialize the PKCS#11 library.
     */
    fun C_Initialize(pInitArgs: Pointer?): NativeLong

    /**
     * List slots that optionally have a token present.
     */
    fun C_GetSlotList(tokenPresent: Byte, pSlotList: Pointer?, pulCount: Pointer?): NativeLong

    /**
     * Gather information about a particular token in the specified slot.
     */
    fun C_GetTokenInfo(slotID: NativeLong, pInfo: Pointer?): NativeLong
}

private const val CKR_OK = 0L
private const val CKR_CRYPTOKI_ALREADY_INITIALIZED = 0x191L

private const val CK_TOKEN_INFO_LABEL_OFFSET = 0
private const val CK_TOKEN_INFO_LABEL_LEN = 32
private const val CK_TOKEN_INFO_MANUFACTURER_LEN = 32
private const val CK_TOKEN_INFO_MODEL_LEN = 16
private const val CK_TOKEN_INFO_SERIAL_OFFSET =
    CK_TOKEN_INFO_LABEL_LEN + CK_TOKEN_INFO_MANUFACTURER_LEN + CK_TOKEN_INFO_MODEL_LEN
private const val CK_TOKEN_INFO_SERIAL_LEN = 16
private const val CK_TOKEN_INFO_SIZE = 256
