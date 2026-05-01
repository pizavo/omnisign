package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.repository.ConfigRepository
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * Read-only diagnostic probe over the PKCS#11 discovery layer.
 *
 * Produces a [Pkcs11DiagnosticsReport] describing exactly what discovery sees on the host,
 * with per-candidate subprocess timings and out-of-process p11-kit truth (Linux).  Used by
 * the `omnisign diagnose pkcs11` CLI subcommand to remove guesswork from troubleshooting.
 *
 * The service deliberately does **not** participate in the warmup / [Pkcs11SessionManager]
 * machinery.  Probes run **sequentially** so each candidate's wall-clock cost is reported in
 * isolation, free of parallel-thrash distortion that the production warmup path produces on
 * weak hardware.  The same dedup helper used by [Pkcs11Discoverer.discoverTokens] is reused
 * to build the final [cz.pizavo.omnisign.domain.service.TokenInfo] list, so the report
 * faithfully reflects what discovery would emit.
 *
 * @property pkcs11Discoverer Application-scope discoverer used for candidate enumeration helpers
 *   and the shared dedup logic.
 * @property configRepository Source of the user-supplied PKCS#11 library list.
 * @property probeTimeoutSeconds Maximum time to wait for a single library probe before
 *   forcibly killing the subprocess.  Defaults to [DEFAULT_PROBE_TIMEOUT_SECONDS].
 * @property externalCommandTimeoutSeconds Maximum time to wait for `p11-kit` / `pkg-config`
 *   helper commands.  Short by design — diagnostics should not stall on a hung tool.
 */
class Pkcs11DiagnosticsService(
	private val pkcs11Discoverer: Pkcs11Discoverer,
	private val configRepository: ConfigRepository,
	private val pcscMonitor: PcscMonitorService,
	private val probeTimeoutSeconds: Long = DEFAULT_PROBE_TIMEOUT_SECONDS,
	private val externalCommandTimeoutSeconds: Long = DEFAULT_EXTERNAL_COMMAND_TIMEOUT_SECONDS,
) {

	/**
	 * Run the full diagnostic sweep and return a structured report.
	 *
	 * Steps, in order: collect environment, enumerate candidates per layer, query
	 * `pkg-config` / `p11-kit` (Linux), enumerate PC/SC readers, run a fresh subprocess
	 * probe per merged candidate **sequentially**, and finally compute the deduplicated
	 * [cz.pizavo.omnisign.domain.service.TokenInfo] list via [Pkcs11Discoverer.buildTokenInfoList].
	 *
	 * @param appDataPkcs11Dir Optional drop directory for user-placed PKCS#11 libraries;
	 *   when omitted, the platform-appropriate default under `<appData>/omnisign/pkcs11/`
	 *   is used so the diagnostic run mirrors what the application sees at runtime.
	 * @return The completed report.
	 */
	suspend fun runDiagnostics(
		appDataPkcs11Dir: File? = null,
	): Pkcs11DiagnosticsReport {
		val startNanos = System.nanoTime()

		val environment = collectEnvironment()
		val effectiveDropDir = appDataPkcs11Dir ?: defaultPkcs11DropDir()

		val config = configRepository.getCurrentConfig()
		val userLibraries = config.global.customPkcs11Libraries.map { it.name to it.path }

		val osLower = System.getProperty("os.name").lowercase()
		val candidatesByLayer = collectLayerBreakdown(osLower, environment.jvmIs64Bit, effectiveDropDir, userLibraries)

		val mergedCandidates = pkcs11Discoverer
			.collectCandidates(effectiveDropDir, userLibraries)
			.map { (name, path) -> toCandidate(name, path) }

		val p11Kit = if (isLinux(osLower)) collectP11KitTruth() else null
		val pcscReaders = pcscMonitor.currentReaders().map { reader ->
			val cardTag = if (reader.cardPresent) {
				reader.atrHex?.let { " — card present (ATR $it)" } ?: " — card present"
			} else " — empty"
			"${reader.name}$cardTag"
		}

		val probeOutcomes = mutableListOf<Pkcs11DiagnosticsReport.ProbeOutcome>()
		val probedTriples = mergedCandidates.map { candidate ->
			val identities = runTimedProbe(candidate.name, candidate.path, probeOutcomes)
			Triple(candidate.name, candidate.path, identities)
		}

		val tokens = pkcs11Discoverer.buildTokenInfoList(probedTriples).map { token ->
			Pkcs11DiagnosticsReport.TokenSummary(
				id = token.id,
				name = token.name,
				type = token.type.name,
				path = token.path,
				requiresPin = token.requiresPin,
			)
		}

		val totalElapsed = (System.nanoTime() - startNanos) / NANOS_PER_MILLI

		return Pkcs11DiagnosticsReport(
			environment = environment,
			candidatesByLayer = candidatesByLayer,
			mergedCandidates = mergedCandidates,
			p11Kit = p11Kit,
			pcscReaders = pcscReaders,
			probes = probeOutcomes.toList().sortedBy { it.path },
			tokens = tokens,
			totalElapsedMillis = totalElapsed,
		)
	}

	/**
	 * Build the [Pkcs11DiagnosticsReport.Environment] block from JVM system properties and
	 * the [java.lang.management.RuntimeMXBean] argument list.
	 */
	private fun collectEnvironment(): Pkcs11DiagnosticsReport.Environment {
		val classpath = System.getProperty("java.class.path") ?: ""
		val jvmArgs = runCatching { ManagementFactory.getRuntimeMXBean().inputArguments }.getOrDefault(emptyList())
		val nativeAccessFlag = jvmArgs.any { arg -> arg.startsWith("--enable-native-access") }
		return Pkcs11DiagnosticsReport.Environment(
			osName = System.getProperty("os.name") ?: "unknown",
			osArch = System.getProperty("os.arch") ?: "unknown",
			osVersion = System.getProperty("os.version") ?: "unknown",
			jvmIs64Bit = System.getProperty("sun.arch.data.model") == "64",
			javaVersion = System.getProperty("java.version") ?: "unknown",
			javaHome = System.getProperty("java.home") ?: "unknown",
			classpathChars = classpath.length,
			nativeAccessFlagPresent = nativeAccessFlag,
		)
	}

	/**
	 * Compute the per-layer candidate breakdown, mirroring [Pkcs11Discoverer.collectCandidates]
	 * but preserving each layer's contribution separately.
	 */
	private fun collectLayerBreakdown(
		osLower: String,
		jvmIs64Bit: Boolean,
		dropDir: File?,
		userLibraries: List<Pair<String, String>>,
	): Pkcs11DiagnosticsReport.CandidatesByLayer {
		val osNative = pkcs11Discoverer.discoverViaOs(osLower, jvmIs64Bit)
			.map { (name, path) -> toCandidate(name, path) }

		val drop = dropDir
			?.takeIf { it.isDirectory }
			?.listFiles { f -> f.isFile && pkcs11Discoverer.isPkcs11FileName(f.name) }
			?.map { f -> toCandidate(pkcs11Discoverer.deriveMiddlewareName(f.absolutePath), f.absolutePath) }
			.orEmpty()

		val user = userLibraries
			.filter { (_, path) -> File(path).exists() }
			.map { (name, path) -> toCandidate(name, path) }

		return Pkcs11DiagnosticsReport.CandidatesByLayer(
			osNative = osNative,
			dropDir = drop,
			userSupplied = user,
		)
	}

	/**
	 * Convert a `(name, path)` pair into a [Pkcs11DiagnosticsReport.Candidate], reading file
	 * existence and size as a snapshot.
	 */
	private fun toCandidate(name: String, path: String): Pkcs11DiagnosticsReport.Candidate {
		val file = File(path)
		val exists = file.exists()
		val size = if (exists && file.isFile) file.length() else null
		return Pkcs11DiagnosticsReport.Candidate(name, path, exists, size)
	}

	/**
	 * Run a single timed subprocess probe for [libraryPath], record the outcome in
	 * [outcomes], and return the parsed token identities so the dedup helper can apply
	 * its rules to them.
	 */
	private fun runTimedProbe(
		name: String,
		libraryPath: String,
		outcomes: MutableList<Pkcs11DiagnosticsReport.ProbeOutcome>,
	): List<Pkcs11TokenIdentity> {
		val startNanos = System.nanoTime()
		val result = runCatching { runProbeSubprocess(libraryPath, probeTimeoutSeconds) }.getOrNull()
		val totalMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI

		val (outcome, identities) = when (result) {
			null -> Pkcs11DiagnosticsReport.ProbeOutcome(
				name = name,
				path = libraryPath,
				outcome = Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.NO_COMMAND,
				totalMillis = totalMillis,
				pid = null,
				exitCode = null,
				stderrSnippet = null,
				identities = emptyList(),
			) to emptyList()

			is Pkcs11SubprocessResult.TimedOut -> Pkcs11DiagnosticsReport.ProbeOutcome(
				name = name,
				path = libraryPath,
				outcome = Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.TIMED_OUT,
				totalMillis = totalMillis,
				pid = result.pid,
				exitCode = null,
				stderrSnippet = null,
				identities = emptyList(),
			) to emptyList()

			is Pkcs11SubprocessResult.Crashed -> Pkcs11DiagnosticsReport.ProbeOutcome(
				name = name,
				path = libraryPath,
				outcome = Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.CRASHED,
				totalMillis = totalMillis,
				pid = result.pid,
				exitCode = result.exitCode,
				stderrSnippet = result.stderr.takeIf { it.isNotBlank() },
				identities = emptyList(),
			) to emptyList()

			is Pkcs11SubprocessResult.Success -> {
				val parsed = result.stdout.lines()
					.filter { it.contains('\t') }
					.map { line ->
						val (label, serial) = line.split('\t', limit = 2)
						Pkcs11TokenIdentity(label, serial, libraryPath)
					}
				val reportIdentities = parsed.map {
					Pkcs11DiagnosticsReport.Identity(it.label, it.serialNumber)
				}
				Pkcs11DiagnosticsReport.ProbeOutcome(
					name = name,
					path = libraryPath,
					outcome = Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.SUCCESS,
					totalMillis = totalMillis,
					pid = result.pid,
					exitCode = 0,
					stderrSnippet = null,
					identities = reportIdentities,
				) to parsed
			}
		}

		outcomes.add(outcome)
		return identities
	}

	/**
	 * Probe the system `pkg-config` and `p11-kit` toolchain for authoritative answers about
	 * registered PKCS#11 modules and observable tokens.  Each invocation is bounded by
	 * [externalCommandTimeoutSeconds] so a hung tool cannot stall the diagnostic run.
	 */
	private fun collectP11KitTruth(): Pkcs11DiagnosticsReport.P11KitTruth {
		return Pkcs11DiagnosticsReport.P11KitTruth(
			version = runExternalCommand("p11-kit", "--version"),
			pkgConfigProxyPath = runExternalCommand("pkg-config", "--variable=proxy_module", "p11-kit-1"),
			listModulesOutput = runExternalCommand("p11-kit", "list-modules"),
			listTokensOutput = runExternalCommand("p11-kit", "list-tokens"),
		)
	}

	/**
	 * Run an external command, capture its stdout, and return it trimmed.
	 *
	 * Returns `null` if the binary is not on `PATH`, the command exits non-zero, or it
	 * does not finish within [externalCommandTimeoutSeconds].
	 */
	private fun runExternalCommand(vararg command: String): String? {
		val process = runCatching { ProcessBuilder(*command).redirectErrorStream(false).start() }
			.getOrElse { return null }
		return try {
			val finished = process.waitFor(externalCommandTimeoutSeconds, TimeUnit.SECONDS)
			if (!finished) {
				process.destroyForcibly()
				return null
			}
			if (process.exitValue() != 0) return null
			process.inputStream.bufferedReader().use { it.readText() }.trim().ifBlank { null }
		} catch (_: Exception) {
			if (process.isAlive) process.destroyForcibly()
			null
		}
	}

	/**
	 * Resolve the platform-appropriate default PKCS#11 drop directory: `<appData>/omnisign/pkcs11/`.
	 *
	 * Mirrors the path resolution in [DssTokenService] so the diagnostic run sees exactly
	 * what the application sees at runtime.  The directory does not need to exist.
	 */
	private fun defaultPkcs11DropDir(): File {
		val osLower = System.getProperty("os.name").lowercase()
		val userHome = System.getProperty("user.home")
		val base = when {
			osLower.contains("win") -> System.getenv("APPDATA")?.let { File(it, "omnisign") }
				?: File(userHome, "AppData/Roaming/omnisign")
			osLower.contains("mac") -> File(userHome, "Library/Application Support/omnisign")
			else -> File(userHome, ".config/omnisign")
		}
		return File(base, "pkcs11")
	}

	private fun isLinux(osLower: String): Boolean =
		!osLower.contains("win") && !osLower.contains("mac")

	private companion object {
		/**
		 * Default ceiling for `p11-kit` / `pkg-config` invocations.  Keeps the diagnostic
		 * run bounded even when a tool hangs.
		 */
		const val DEFAULT_EXTERNAL_COMMAND_TIMEOUT_SECONDS = 10L

		/**
		 * Conversion factor used to express nanosecond differences in milliseconds.
		 */
		const val NANOS_PER_MILLI = 1_000_000L
	}
}
