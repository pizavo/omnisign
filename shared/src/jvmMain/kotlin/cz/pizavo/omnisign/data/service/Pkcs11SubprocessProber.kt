package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Process-isolated [Pkcs11Prober] implementation.
 *
 * Consolidates the probe-spawn logic that was previously split between
 * `Pkcs11Discoverer` (`resolveProbeClasspath`/`resolveProbeCommand`/
 * `probeTokenIdentitiesViaSubprocess`/`parseProbeStdout`) and `Pkcs11SubprocessResult`
 * (`runProbeSubprocess`/`runCertProbeSubprocess`/`runResolvedProbeSubprocess`) into one
 * cohesive component.  A fatal native crash (e.g. SafeNet `libeTPKCS15.so` SIGSEGV with
 * no card) only kills the child process — the host JVM continues.
 *
 * @property probeTimeoutSeconds Wall-clock kill timeout for [probeIdentities]; a safety
 *   net for middleware that hangs (crashed probes exit immediately regardless).
 */
class Pkcs11SubprocessProber(
	private val probeTimeoutSeconds: Long = Pkcs11Prober.DEFAULT_PROBE_TIMEOUT_SECONDS,
) : Pkcs11Prober {

	/** Probe [libraryPath] for token identities; see [Pkcs11Prober.probeIdentities]. */
	override fun probeIdentities(libraryPath: String): List<Pkcs11TokenIdentity> {
		return runCatching {
			when (val result = runProbe(libraryPath, probeTimeoutSeconds)) {
				null -> {
					logger.warn { "Cannot resolve probe command — skipping probe for '$libraryPath'" }
					emptyList()
				}

				is Pkcs11SubprocessResult.TimedOut -> {
					logger.warn {
						"PKCS#11 probe subprocess pid=${result.pid} for '$libraryPath' timed out after ${probeTimeoutSeconds}s"
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

				is Pkcs11SubprocessResult.Success -> parseIdentities(result.stdout, libraryPath)
			}
		}.getOrElse { e ->
			logger.warn(e) { "Failed to spawn PKCS#11 probe subprocess for '$libraryPath'" }
			emptyList()
		}
	}

	/** Spawn a probe subprocess; see [Pkcs11Prober.runProbe]. */
	override fun runProbe(libraryPath: String, timeoutSeconds: Long): Pkcs11SubprocessResult? {
		val command = resolveProbeCommand(libraryPath) ?: return null
		return runResolvedProbeSubprocess(command, libraryPath, timeoutSeconds)
	}

	/** Spawn a `--certs` probe subprocess; see [Pkcs11Prober.runCertProbe]. */
	override fun runCertProbe(libraryPath: String, timeoutSeconds: Long): Pkcs11SubprocessResult? {
		val command = resolveProbeCommand(libraryPath) ?: return null
		return runResolvedProbeSubprocess(command + "--certs", libraryPath, timeoutSeconds)
	}

	/** Enumerate p11-kit-registered module paths; see [Pkcs11Prober.discoverModulePaths]. */
	override fun discoverModulePaths(timeoutSeconds: Long): List<String> {
		val command = resolveWorkerCommand(
			Pkcs11ModuleDiscoveryWorker::class.java.name,
			"discover-modules",
			emptyList(),
		)
		if (command == null) {
			logger.warn { "Cannot resolve module-discovery command — skipping libp11-kit discovery" }
			return emptyList()
		}
		return runCatching {
			when (val result = runResolvedProbeSubprocess(command, "libp11-kit module discovery", timeoutSeconds)) {
				is Pkcs11SubprocessResult.TimedOut -> {
					logger.warn {
						"libp11-kit module-discovery subprocess pid=${result.pid} timed out after ${timeoutSeconds}s"
					}
					emptyList()
				}

				is Pkcs11SubprocessResult.Crashed -> {
					logger.warn {
						buildString {
							append("libp11-kit module-discovery subprocess pid=${result.pid} exited with code ${result.exitCode}")
							if (result.stderr.isNotEmpty()) append("\n  stderr: ${result.stderr}")
						}
					}
					emptyList()
				}

				is Pkcs11SubprocessResult.Success -> {
					val paths = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
					if (result.stderr.isNotEmpty()) {
						if (paths.isEmpty()) {
							logger.warn {
								"libp11-kit module-discovery subprocess pid=${result.pid} produced no modules — " +
										"stderr: ${result.stderr}"
							}
						} else {
							logger.debug { "libp11-kit module-discovery subprocess pid=${result.pid} stderr: ${result.stderr}" }
						}
					}
					paths
				}
			}
		}.getOrElse { e ->
			logger.warn(e) { "Failed to spawn libp11-kit module-discovery subprocess" }
			emptyList()
		}
	}

	/**
	 * Parse a probe subprocess's `stdout` into [Pkcs11TokenIdentity] rows.
	 *
	 * Each non-empty tab-separated line carries `label\tserialNumber\tslotId` (current
	 * format) or `label\tserialNumber` (legacy two-column, slot ID falls back to `0`).
	 * Lines without a tab are dropped; non-numeric slot IDs degrade to `0`.
	 */
	override fun parseIdentities(stdout: String, libraryPath: String): List<Pkcs11TokenIdentity> =
		stdout.lines()
			.filter { it.contains('\t') }
			.mapNotNull { line ->
				val parts = line.split('\t')
				val (label, serial, slotIdRaw) = when (parts.size) {
					3 -> Triple(parts[0], parts[1], parts[2])
					2 -> Triple(parts[0], parts[1], "0")
					else -> return@mapNotNull null
				}
				val slotId = slotIdRaw.toLongOrNull() ?: 0L
				Pkcs11TokenIdentity(
					label = label,
					serialNumber = serial,
					libraryPath = libraryPath,
					slotId = slotId,
				)
			}

	/**
	 * Resolve the classpath for spawning a [Pkcs11ProbeWorker] via `java`.
	 *
	 * 1. `java.class.path` system property (IDE / `java -cp` / most jpackage launchers).
	 * 2. Code-source JAR directory scan when (1) is null/blank (some jpackage images):
	 *    every `*.jar` beside the JAR containing [Pkcs11ProbeWorker].
	 *
	 * @return the classpath string, or `null` when neither strategy yields a usable path.
	 */
	private fun resolveProbeClasspath(): String? {
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
	 * Build the [Pkcs11ProbeWorker] subprocess command line for [libraryPath].
	 *
	 * Thin wrapper over [resolveWorkerCommand] for the single-library probe worker.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
	 * @return the command list, or `null` when no usable executable can be found.
	 */
	private fun resolveProbeCommand(libraryPath: String): List<String>? =
		resolveWorkerCommand(Pkcs11ProbeWorker::class.java.name, "probe", listOf(libraryPath))

	/**
	 * Build a worker subprocess command line, shared by every isolated worker.
	 *
	 * 1. `java` binary in `java.home/bin/` (standard JVM) — requires [resolveProbeClasspath],
	 *    invoking `<workerClassName> <trailingArgs...>`.
	 * 2. Native launcher fallback ([ProcessHandle.current]) invoked with
	 *    `<nativeSubcommand> <trailingArgs...>` — jpackage strips the `java` binary but the
	 *    native launcher is always present and dispatches the subcommand to the same worker.
	 *
	 * @param workerClassName Fully-qualified name of the worker's `@JvmStatic main` class
	 *   (the `java -cp` entry point).
	 * @param nativeSubcommand Argument the native launcher dispatches on (e.g. `probe`,
	 *   `discover-modules`).
	 * @param trailingArgs Arguments passed to the worker after the entry point / subcommand.
	 * @return the command list, or `null` when no usable executable can be found.
	 */
	private fun resolveWorkerCommand(
		workerClassName: String,
		nativeSubcommand: String,
		trailingArgs: List<String>,
	): List<String>? {
		val javaBinaryName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
		val javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaBinaryName).toString()
		if (File(javaExecutable).exists()) {
			val classpath = resolveProbeClasspath()
			if (classpath == null) {
				logger.warn { "java binary found but classpath resolution failed — cannot spawn '$nativeSubcommand' worker" }
				return null
			}
			return buildList {
				add(javaExecutable)
				add("--enable-native-access=ALL-UNNAMED")
				System.getProperty("omnisign.crash.dir")?.let { crashDir ->
					add("-XX:ErrorFile=$crashDir/hs_err_pid%p.log")
				}
				add("-cp")
				add(classpath)
				add(workerClassName)
				addAll(trailingArgs)
			}
		}

		logger.info { "java binary not found at '$javaExecutable' — trying native launcher fallback" }

		val nativeLauncher = ProcessHandle.current().info().command().orElse(null)
		if (nativeLauncher != null && File(nativeLauncher).exists()) {
			logger.info { "Using native launcher for '$nativeSubcommand': $nativeLauncher" }
			return buildList {
				add(nativeLauncher)
				add(nativeSubcommand)
				addAll(trailingArgs)
			}
		}

		logger.warn { "Neither java binary nor native launcher found — cannot spawn '$nativeSubcommand' worker" }
		return null
	}

	/**
	 * Shared subprocess lifecycle: spawn [command], drain stdout/stderr on daemon threads
	 * (avoiding the 64 KB pipe-buffer deadlock), wait up to [timeoutSeconds], classify.
	 * A `finally` forcibly kills a leaked process so discovery never hangs.
	 */
	private fun runResolvedProbeSubprocess(
		command: List<String>,
		description: String,
		timeoutSeconds: Long,
	): Pkcs11SubprocessResult {
		logger.debug { "Spawning PKCS#11 subprocess: ${command.first()}, target=$description" }

		val process = ProcessBuilder(command).start()
		val pid = process.pid()
		logger.debug { "PKCS#11 subprocess pid=$pid started for '$description'" }

		try {
			val stdoutResult = CompletableFuture<String>()
			val stderrResult = CompletableFuture<String>()

			thread(isDaemon = true, name = "pkcs11-stdout-$pid") {
				stdoutResult.complete(
					runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
				)
			}
			thread(isDaemon = true, name = "pkcs11-stderr-$pid") {
				stderrResult.complete(
					runCatching { process.errorStream.bufferedReader().readText().trim() }.getOrDefault("")
				)
			}

			val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

			if (!completed) {
				process.destroyForcibly()
				process.waitFor(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				return Pkcs11SubprocessResult.TimedOut(pid)
			}

			val exitCode = process.exitValue()
			if (exitCode != 0) {
				val stderr = runCatching {
					stderrResult.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				}.getOrDefault("")
				return Pkcs11SubprocessResult.Crashed(pid, exitCode, stderr.take(MAX_STDERR_LOG_CHARS))
			}

			val stdout = runCatching {
				stdoutResult.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			}.getOrDefault("")
			val stderr = runCatching {
				stderrResult.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			}.getOrDefault("")
			return Pkcs11SubprocessResult.Success(pid, stdout, stderr.take(MAX_STDERR_LOG_CHARS))
		} finally {
			if (process.isAlive) {
				logger.debug { "Destroying leaked subprocess pid=$pid for '$description'" }
				process.destroyForcibly()
			}
		}
	}

	private companion object {
		val logger = KotlinLogging.logger {}

		/**
		 * Max characters of subprocess stderr to include in log messages, so a crashed
		 * library dumping a large native stack trace cannot flood the log.
		 */
		const val MAX_STDERR_LOG_CHARS = 2000

		/**
		 * Grace period (seconds) for draining stdout/stderr after the process exits or is
		 * forcibly killed, and for awaiting termination of a killed process.
		 */
		const val STREAM_DRAIN_TIMEOUT_SECONDS = 5L
	}
}
