package cz.pizavo.omnisign.data.service

import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo
import java.io.File
import java.util.*

/**
 * Discovers PKCS#11 middleware libraries available on the current system.
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
 * Duplicates are resolved by canonical path; the first-seen name wins.
 */
class Pkcs11Discoverer {

    /**
     * Discover all available PKCS#11 tokens and return a [TokenInfo] for each.
     *
     * @param appDataPkcs11Dir Optional drop directory.  Every PKCS#11-named file found here
     *   is included automatically without any config change.  Pass `null` to skip.
     * @param userPkcs11Libraries Caller-supplied `(name, absolutePath)` pairs, e.g., from
     *   [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries].
     */
    fun discoverTokens(
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

        return seen.values.map { (name, path) ->
            TokenInfo(
                id = "pkcs11-${File(path).name}",
                name = name,
                type = TokenType.PKCS11,
                path = path,
                requiresPin = true,
            )
        }
    }

    /**
     * Query OS-native sources for PKCS#11 middleware without touching the fallback list.
     *
     * - **Windows**: PC/SC via `SCardListReaders`, vendor registry trees, `System32` dir scan.
     * - **macOS**: `security list-smartcards`, `pluginkit -mAT com.apple.ctk.token`, p11-kit.
     * - **Linux**: p11-kit `*.module` files.
     *
     * Never throws; returns an empty list when the OS mechanism is unavailable.
     */
    internal fun discoverViaOs(
        os: String = System.getProperty("os.name").lowercase(),
        jvmIs64Bit: Boolean = System.getProperty("sun.arch.data.model") == "64",
    ): List<Pair<String, String>> = when {
        os.contains("win") -> discoverViaPcsc() +
                discoverViaWindowsRegistry(jvmIs64Bit) +
                discoverViaDirScan(jvmIs64Bit)

        os.contains("mac") -> discoverViaMacOsSecurity() + discoverViaP11Kit()

        else -> discoverViaP11Kit()
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
            "OpenSC" to "/Library/OpenSC/lib/opensc-pkcs11.so",
            "OpenSC (Homebrew)" to "/opt/homebrew/lib/opensc-pkcs11.so",
            "macOS Smart Card" to "/usr/lib/libctkpcscd.dylib",
            "SoftHSM2 (Homebrew)" to "/opt/homebrew/lib/softhsm/libsofthsm2.so",
        )

        jvmIs64Bit -> listOf(
            "OpenSC" to "/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so",
            "OpenSC (aarch64)" to "/usr/lib/aarch64-linux-gnu/opensc-pkcs11.so",
            "OpenSC (local)" to "/usr/local/lib/opensc-pkcs11.so",
            "SoftHSM2" to "/usr/lib/softhsm/libsofthsm2.so",
            "SoftHSM2 (lib64)" to "/usr/lib64/softhsm/libsofthsm2.so",
        )

        else -> listOf(
            "OpenSC" to "/usr/lib/opensc-pkcs11.so",
            "OpenSC (local)" to "/usr/local/lib/opensc-pkcs11.so",
            "SoftHSM2" to "/usr/lib/softhsm/libsofthsm2.so",
        )
    }

    /**
     * Return `true` when [fileName] (base name only) looks like a PKCS#11 provider library.
     * Matches names containing `pkcs11`, `p11`, `etpkcs`, `gclib`, `opensc`, `iidp11`,
     * `cmp11`, `softhsm`, `libsac`, `libck`, or `cryptoki`.
     */
    internal fun isPkcs11FileName(fileName: String): Boolean =
        PKCS11_NAME_PATTERNS.any { fileName.lowercase().contains(it) }

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
            lower.contains("opensc") -> "OpenSC"
            lower.contains("iidp11") || lower.contains("netid") -> "SecMaker Net iD"
            lower.contains("cmp11") || lower.contains("charismathics") -> "Charismathics PKCS#11"
            lower.contains("softhsm") -> "SoftHSM2"
            lower.contains("libck") -> "Cryptoki Library"
            else -> libraryPath.substringAfterLast('/').substringAfterLast('\\')
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
     * Parse p11-kit `*.module` files from standard search paths.
     *
     * Each file is a simple `key: value` format.  Reads `module:` (library path) and
     * optionally `name:` / `description:` for the display name.
     *
     * Search paths:
     * - `/etc/pkcs11/modules`
     * - `/usr/share/p11-kit/modules`
     * - `~/.config/pkcs11/modules`
     * - `/Library/Application Support/p11-kit/modules` (macOS)
     * - directory of `$P11_KIT_CONFIG_FILE` (if set)
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
        /**
         * Maximum ATR length defined by ISO/IEC 7816-3: 32 bytes of ATR + 1 TCK byte.
         */
        const val ATR_MAX_SIZE_BYTES = 33

        val PKCS11_NAME_PATTERNS = listOf(
            "pkcs11", "p11", "etpkcs", "gclib", "opensc", "iidp11", "cmp11",
            "softhsm", "libsac", "libck", "cryptoki",
        )
    }
}

