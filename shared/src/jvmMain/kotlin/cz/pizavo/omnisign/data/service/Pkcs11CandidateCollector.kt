package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Enumerates candidate PKCS#11 middleware library paths from every discovery source,
 * deduplicated by absolute path, **without** probing any of them.
 *
 * Sources, merged in this order (first wins on path collision):
 * 1. OS-native sources ([discoverViaOs]) — PC/SC + Calais on Windows (via
 *    [Pkcs11PcscCalaisResolver]), `security` / `pluginkit` plus the p11-kit proxy on
 *    macOS, the p11-kit proxy on Linux.
 * 2. App-data drop directory — any PKCS#11-named file under `<appDataDir>/omnisign/pkcs11/`.
 * 3. User-supplied paths — entries from
 *    [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries].
 *
 * Results are cached by `(appDataPkcs11Dir, userPkcs11Libraries)` because the composition
 * of installed middleware changes only on a vendor install/uninstall or a reader
 * plug/unplug.  Cache lifetime is **event-driven**: entries live indefinitely until
 * [Pkcs11CacheInvalidator] observes a PC/SC reader-state change and calls
 * [invalidateCandidates], or a caller triggers an explicit rescan.
 *
 * @property pcscCalaisResolver Windows PC/SC + Calais ATR → library-path resolver; only
 *   consulted on Windows.  Injected so the smart-card stack stays isolated and testable.
 */
class Pkcs11CandidateCollector(
	private val pcscCalaisResolver: Pkcs11PcscCalaisResolver = Pkcs11PcscCalaisResolver(),
) {

	/**
	 * Cache of [collectCandidates] results, keyed by `(appDataPkcs11Dir, userPkcs11Libraries)`.
	 *
	 * `collectCandidates` is the cold dominator of every dialog open: PC/SC enumeration on
	 * Windows, and the p11-kit proxy on Linux (plus `security` / `pluginkit` on macOS).
	 * Entries live indefinitely and are cleared by [Pkcs11CacheInvalidator] on reader plug /
	 * unplug events; explicit user rescan goes through [invalidateCandidates].
	 */
	private val candidateCache = ConcurrentHashMap<CandidateCacheKey, List<Pair<String, String>>>()

	/**
	 * Composite cache key reflecting the inputs that determine the candidate list.
	 *
	 * @property dropDirPath Absolute path of the app-data drop directory, or `null` when none.
	 *   Two opens with different drop directories must not share a cache entry.
	 * @property userLibraryPaths Snapshot of user-supplied library paths (by absolute path,
	 *   names dropped because they don't affect candidate selection).
	 */
	private data class CandidateCacheKey(
		val dropDirPath: String?,
		val userLibraryPaths: List<String>,
	)

	/**
	 * Drop every cached candidate enumeration so the next [collectCandidates] call
	 * re-runs the OS-native discovery branches.
	 *
	 * Used when the set of installed PKCS#11 libraries may have changed — typically a
	 * smart-card reader being plugged in or unplugged, which can alter PC/SC + Calais
	 * mappings or which p11-kit modules are reachable.
	 */
	fun invalidateCandidates() {
		candidateCache.clear()
		logger.debug { "Candidate cache cleared" }
	}

	/**
	 * Enumerate all unique candidate PKCS#11 library paths without probing them.
	 *
	 * This is the discovery-only first phase: OS-native sources, the app-data drop directory,
	 * and user-supplied libraries are merged and deduplicated by absolute path.  No
	 * subprocess is spawned (other than the OS-discovery sub-helpers' own spawns, which are
	 * themselves rate-limited and gated on prerequisites) and no PKCS#11 function is called.
	 *
	 * Used by [Pkcs11WarmupService] to obtain the candidate set for background initialization
	 * and by [Pkcs11Discoverer.discoverTokens] as the first step before parallel probing.
	 *
	 * Repeated calls with the same `(appDataPkcs11Dir, userPkcs11Libraries)` return the
	 * cached list without re-running PC/SC, registry, or directory scans; this is the
	 * dominant cost on every dialog open and the cache turns second-and-onward opens into
	 * a sub-millisecond lookup.  Cache entries live indefinitely; [Pkcs11CacheInvalidator]
	 * clears them on reader plug / unplug events, and explicit user rescans go through
	 * [invalidateCandidates].
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
		val cacheKey = CandidateCacheKey(
			dropDirPath = appDataPkcs11Dir?.absolutePath,
			userLibraryPaths = userPkcs11Libraries.map { it.second },
		)
		candidateCache[cacheKey]?.let {
			logger.debug { "Candidate cache hit (${it.size} entry(-ies))" }
			return it
		}
		val os = System.getProperty("os.name").lowercase()
		val jvmIs64Bit = System.getProperty("sun.arch.data.model") == "64"
		val candidates = collectCandidatesUncached(os, jvmIs64Bit, appDataPkcs11Dir, userPkcs11Libraries)
		candidateCache[cacheKey] = candidates
		return candidates
	}

	/**
	 * Compute the candidate list from scratch, bypassing [candidateCache].
	 *
	 * Extracted from [collectCandidates] so that the cache lookup wraps a single uncached
	 * call site, and so warmup can offer to populate the cache without going through the
	 * cache-lookup branch itself (the warmup result IS the first cache fill).
	 */
	private fun collectCandidatesUncached(
		os: String,
		jvmIs64Bit: Boolean,
		appDataPkcs11Dir: File?,
		userPkcs11Libraries: List<Pair<String, String>>,
	): List<Pair<String, String>> {
		val seen = LinkedHashMap<String, Pair<String, String>>()

		fun merge(candidates: List<Pair<String, String>>) {
			for ((name, path) in candidates) {
				val key = File(path).absolutePath
				seen.putIfAbsent(key, name to path)
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
	 * Query OS-native sources for PKCS#11 middleware.
	 *
	 * Each platform delegates to its OS PKCS#11 manager — the standard registration mechanism
	 * a well-behaved vendor installer uses — and nothing else.  Libraries that bypass the
	 * OS manager (an installer that skipped registration, a manually-deployed library, an
	 * out-of-tree development build) reach discovery via the user-controlled escape hatches
	 * documented in [collectCandidates]: the app-data drop directory and the
	 * `customPkcs11Libraries` config entry.
	 *
	 * - **Windows**: PC/SC + Calais ATR mapping via [Pkcs11PcscCalaisResolver]
	 *   (`SCardListReaders` → `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`).
	 * - **macOS**: `security list-smartcards`, `pluginkit -mAT com.apple.ctk.token`, plus the
	 *   p11-kit *proxy* if the user installed p11-kit via Homebrew.
	 * - **Linux**: the p11-kit *proxy* library.
	 *
	 * The proxy is a single PKCS#11 module that aggregates every module registered with
	 * p11-kit, so probing it loads the whole p11-kit registry in one subprocess instead of
	 * N — meaningful on multi-token systems and noticeable even with our parallelism cap of
	 * two.  It also honours `.module` directives a naive parser ignores (`disable-in:`,
	 * `enable-in:`, priority ordering, env-var overrides).  Every modern distribution that
	 * ships `.module` files also ships the proxy in the same package
	 * (Fedora `p11-kit`, Debian/Ubuntu `p11-kit-modules`, Arch `p11-kit`,
	 * Homebrew `p11-kit`), so the proxy is the OS-manager surface — direct `.module` parsing
	 * adds no realistic coverage.  Users with non-registering middleware drop the library
	 * into `<appData>/omnisign/pkcs11/` or add it to `customPkcs11Libraries`.
	 *
	 * The platform-specific branches are independent and run in parallel via short-lived
	 * worker threads.
	 *
	 * Never throws; returns an empty list when an OS mechanism is unavailable.
	 */
	internal fun discoverViaOs(
		os: String = System.getProperty("os.name").lowercase(),
		@Suppress("UNUSED_PARAMETER") jvmIs64Bit: Boolean = System.getProperty("sun.arch.data.model") == "64",
	): List<Pair<String, String>> {
		val branches: List<() -> List<Pair<String, String>>> = when {
			os.contains("win") -> listOf(
				{ pcscCalaisResolver.resolvePkcs11Paths().map { deriveMiddlewareName(it) to it } },
			)

			os.contains("mac") -> listOf(
				{ discoverViaMacOsSecurity() },
				{ discoverViaP11KitProxy() },
			)

			else -> listOf(
				{ discoverViaP11KitProxy() },
			)
		}

		return runBranchesInParallel(branches)
	}

	/**
	 * Execute the selected platform branches concurrently and concatenate their results in
	 * the declared branch order.  The branch set is OS-dependent (one on Windows and Linux,
	 * two on macOS), not a fixed three.
	 *
	 * Uses raw daemon threads with `join` rather than coroutines so that this synchronous
	 * helper stays callable from non-suspending code paths (warmup, diagnostics, tests).  Each
	 * branch is fully self-contained and bounded; we accept the small per-call thread-creation
	 * overhead in exchange for keeping [collectCandidates] non-suspending.
	 *
	 * Branches that throw have their exception swallowed and treated as "no candidates from
	 * this source", matching the legacy synchronous behaviour where each branch was wrapped
	 * in `runCatching` internally.
	 */
	private fun runBranchesInParallel(
		branches: List<() -> List<Pair<String, String>>>,
	): List<Pair<String, String>> {
		val results = arrayOfNulls<List<Pair<String, String>>>(branches.size)
		val threads = branches.mapIndexed { index, branch ->
			Thread({
				results[index] = runCatching { branch() }.getOrDefault(emptyList())
			}, "pkcs11-discover-${index}").apply {
				isDaemon = true
				start()
			}
		}
		threads.forEach { it.join() }
		return results.filterNotNull().flatten()
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
	 * On Linux, [discoverViaOs] prefers the proxy when it's present — it's a single subprocess
	 * load that covers every p11-kit-registered module, with consistent slot IDs.  If the user
	 * also adds direct module paths via the app-data drop directory or `customPkcs11Libraries`,
	 * the proxy and a direct module may report the same physical token.  In that case
	 * [Pkcs11TokenInfoDeduplicator] sorts proxy results last so the direct library's path
	 * wins, because direct paths typically come from explicit user intent and let us pin
	 * SunPKCS11 to a vendor-specific slot ID.
	 */
	internal fun isProxyPath(path: String): Boolean {
		val lower = path.lowercase()
		return lower.contains("p11-kit-proxy") || lower.contains("p11kitproxy")
	}

	/**
	 * Detect the macOS CryptoTokenKit PKCS#11 shim (`/usr/lib/libctkpcscd.dylib`) when a
	 * smart card or CTK token extension is actually present.
	 *
	 * Uses `security list-smartcards` and `pluginkit -mAT com.apple.ctk.token`.
	 * The shim is only returned when at least one card or CTK extension is found, avoiding a
	 * spurious PKCS#11 slot on systems with no tokens.  Other middleware paths (OpenSC, etc.)
	 * are covered by [discoverViaP11KitProxy] when the user has installed p11-kit via
	 * Homebrew, or by the user-controlled escape hatches in [collectCandidates].
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
	 * Load the p11-kit proxy module if present on the system.
	 *
	 * The proxy is a single PKCS#11 library that aggregates every module registered with
	 * p11-kit, so loading it exposes all system-registered tokens through one entry point.
	 * Only the first existing path from [P11_KIT_PROXY_PATHS] is returned — multiple proxy
	 * installations on the same machine are uncommon and the serial-number deduplication
	 * in [Pkcs11TokenInfoDeduplicator] would collapse them anyway.
	 *
	 * Returns an empty list when the proxy library is not found; [discoverViaOs] then simply
	 * contributes no candidates from this source (users with non-registering middleware use
	 * the app-data drop directory or `customPkcs11Libraries`).
	 *
	 * @param proxyPaths Ordered list of candidate proxy paths; override for testing.
	 */
	internal fun discoverViaP11KitProxy(
		proxyPaths: List<String> = P11_KIT_PROXY_PATHS,
	): List<Pair<String, String>> =
		proxyPaths.firstOrNull { File(it).exists() }
			?.let { listOf("p11-kit Proxy" to it) }
			?: emptyList()

	private companion object {
		val logger = KotlinLogging.logger {}

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
		 * Debian/Ubuntu, RPM-based distributions, and manual installations on Linux, plus
		 * Homebrew (`brew install p11-kit`) on Intel and Apple Silicon macOS.
		 */
		val P11_KIT_PROXY_PATHS = listOf(
			"/usr/lib/x86_64-linux-gnu/pkcs11/p11-kit-proxy.so",
			"/usr/lib/aarch64-linux-gnu/pkcs11/p11-kit-proxy.so",
			"/usr/lib64/pkcs11/p11-kit-proxy.so",
			"/usr/lib/pkcs11/p11-kit-proxy.so",
			"/usr/local/lib/pkcs11/p11-kit-proxy.so",
			"/opt/homebrew/lib/pkcs11/p11-kit-proxy.so",
		)
	}
}
