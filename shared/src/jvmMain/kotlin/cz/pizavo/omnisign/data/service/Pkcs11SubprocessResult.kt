package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit

/**
 * Maximum number of characters from subprocess stderr to include in log messages.
 *
 * Shared by all PKCS#11 subprocess callers ([Pkcs11WarmupService], [probeTokenIdentitiesViaSubprocess])
 * to keep log output bounded when a crashed library dumps a large stack trace to stderr.
 */
internal const val MAX_STDERR_LOG_CHARS = 2000

/**
 * Outcome of running a [Pkcs11ProbeWorker] subprocess to completion.
 *
 * Produced by [runProbeSubprocess] after spawning a child process via [resolveProbeCommand]
 * and waiting for it to finish or time out.  Each caller interprets the result differently:
 * - [Pkcs11WarmupService]: [Success] → [Pkcs11SessionManager.registerSafe], failure →
 *   [Pkcs11SessionManager.registerCrashed].
 * - [probeTokenIdentitiesViaSubprocess]: [Success] → parse [Success.stdout] for
 *   [Pkcs11TokenIdentity] lines, failure → empty list.
 */
internal sealed interface Pkcs11SubprocessResult {

	/**
	 * Subprocess exited with code 0 — the library loaded and probed successfully.
	 *
	 * @property pid PID of the child process.
	 * @property stdout Full standard output captured from the subprocess.
	 */
	data class Success(val pid: Long, val stdout: String) : Pkcs11SubprocessResult

	/**
	 * Subprocess exited with a non-zero code (native crash, probing error, etc.).
	 *
	 * @property pid PID of the child process.
	 * @property exitCode The process exit code (values > 128 typically indicate a signal).
	 * @property stderr Standard error output, truncated to [MAX_STDERR_LOG_CHARS] characters.
	 */
	data class Crashed(val pid: Long, val exitCode: Int, val stderr: String) : Pkcs11SubprocessResult

	/**
	 * Subprocess did not complete within the allowed time and was forcibly killed.
	 *
	 * @property pid PID of the killed child process.
	 */
	data class TimedOut(val pid: Long) : Pkcs11SubprocessResult
}

/**
 * Spawn a [Pkcs11ProbeWorker] subprocess for the given [libraryPath] and wait for completion.
 *
 * Encapsulates the shared subprocess lifecycle that both [Pkcs11WarmupService.warmup] and
 * [probeTokenIdentitiesViaSubprocess] previously implemented independently:
 * 1. Resolve the probe command via [resolveProbeCommand].
 * 2. Start the process via [ProcessBuilder].
 * 3. Wait up to [timeoutSeconds] for completion.
 * 4. On timeout → [Process.destroyForcibly] and return [Pkcs11SubprocessResult.TimedOut].
 * 5. On non-zero exit → capture stderr and return [Pkcs11SubprocessResult.Crashed].
 * 6. On exit 0 → capture stdout and return [Pkcs11SubprocessResult.Success].
 *
 * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
 * @param timeoutSeconds Maximum wall-clock time to wait before forcibly killing the subprocess.
 *   Only reached when the process hangs; crashed probes are handled immediately.
 * @return The subprocess outcome, or `null` when [resolveProbeCommand] cannot find a suitable
 *   executable to launch.
 * @throws Exception if [ProcessBuilder.start] fails (e.g., permission denied, missing binary).
 */
internal fun runProbeSubprocess(
	libraryPath: String,
	timeoutSeconds: Long,
): Pkcs11SubprocessResult? {
	val logger = KotlinLogging.logger {}

	val command = resolveProbeCommand(libraryPath) ?: return null

	logger.debug { "Spawning PKCS#11 subprocess: ${command.first()}, library=$libraryPath" }

	val process = ProcessBuilder(command).start()
	val pid = process.pid()
	logger.debug { "PKCS#11 subprocess pid=$pid started for '$libraryPath'" }

	val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

	if (!completed) {
		process.destroyForcibly()
		return Pkcs11SubprocessResult.TimedOut(pid)
	}

	val exitCode = process.exitValue()
	if (exitCode != 0) {
		val stderr = runCatching {
			process.errorStream.bufferedReader().readText().trim()
		}.getOrDefault("")
		return Pkcs11SubprocessResult.Crashed(pid, exitCode, stderr.take(MAX_STDERR_LOG_CHARS))
	}

	val stdout = process.inputStream.bufferedReader().readText()
	return Pkcs11SubprocessResult.Success(pid, stdout)
}

