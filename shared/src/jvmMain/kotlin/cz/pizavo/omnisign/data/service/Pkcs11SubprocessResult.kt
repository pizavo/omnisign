package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Maximum number of characters from subprocess stderr to include in log messages.
 *
 * Shared by all PKCS#11 subprocess callers ([Pkcs11WarmupService], [probeTokenIdentitiesViaSubprocess])
 * to keep log output bounded when a crashed library dumps a large stack trace to stderr.
 */
internal const val MAX_STDERR_LOG_CHARS = 2000

/**
 * Grace period in seconds for draining subprocess stdout/stderr after the process
 * has exited or been forcibly killed, and for waiting for the killed process to
 * actually terminate.
 *
 * After a subprocess exits normally, its pipe buffers are flushed almost instantly;
 * this timeout is a safety net for pathological cases.  After [Process.destroyForcibly],
 * the OS closes the streams, causing the drain threads to finish within milliseconds —
 * the timeout merely caps the wait so the caller is never blocked indefinitely.
 */
internal const val STREAM_DRAIN_TIMEOUT_SECONDS = 5L

/**
 * Outcome of running a [Pkcs11ProbeWorker] subprocess to completion.
 *
 * Produced by [runProbeSubprocess] after spawning a child process via [resolveProbeCommand]
 * and waiting for it to finish or time out.  Each caller interprets the result differently:
 * - [Pkcs11WarmupService]: [Success] → log validation; [Crashed] →
 *   [Pkcs11CrashBlacklist.registerCrashed]; [TimedOut] → no record (retried on demand).
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
 * 3. Immediately launch two daemon threads that drain `stdout` and `stderr` into
 *    [CompletableFuture]s.  This prevents the classic pipe-buffer deadlock: OS pipe
 *    buffers are typically 64 KB, and if the subprocess fills either buffer while the
 *    parent blocks on [Process.waitFor], the child blocks on `write()` and never exits,
 *    causing a spurious timeout.
 * 4. Wait up to [timeoutSeconds] for completion.
 * 5. On timeout → [Process.destroyForcibly], brief grace period, return [Pkcs11SubprocessResult.TimedOut].
 * 6. On non-zero exit → drain stderr future and return [Pkcs11SubprocessResult.Crashed].
 * 7. On exit 0 → drain stdout future and return [Pkcs11SubprocessResult.Success].
 *
 * A `try`/`finally` block guarantees that the subprocess is forcibly killed if the calling
 * thread encounters an unexpected exception (e.g., [InterruptedException], OOM) after the
 * process has been started, so discovery is never left hanging.
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
	val command = resolveProbeCommand(libraryPath) ?: return null
	return runResolvedProbeSubprocess(command, libraryPath, timeoutSeconds)
}

/**
 * Diagnostic-only variant of [runProbeSubprocess] that appends the `--certs` argument so
 * the [Pkcs11ProbeWorker] additionally performs a no-`C_Login` certificate enumeration.
 *
 * Spawned exclusively by [Pkcs11DiagnosticsService]; never by discovery or warmup, so the
 * proven identity-probe path and its callers are entirely unaffected.  Same lifecycle and
 * isolation guarantees as [runProbeSubprocess].
 *
 * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
 * @param timeoutSeconds Maximum wall-clock time before the subprocess is forcibly killed.
 * @return The subprocess outcome, or `null` when no probe command can be resolved.
 */
internal fun runCertProbeSubprocess(
	libraryPath: String,
	timeoutSeconds: Long,
): Pkcs11SubprocessResult? {
	val command = resolveProbeCommand(libraryPath) ?: return null
	return runResolvedProbeSubprocess(command + "--certs", libraryPath, timeoutSeconds)
}

/**
 * Shared subprocess lifecycle for [runProbeSubprocess] / [runCertProbeSubprocess]: spawn
 * [command], drain stdout/stderr on daemon threads, wait up to [timeoutSeconds], and
 * classify the outcome.  Always returns a result; callers map an unresolved command to
 * `null` before calling this.
 */
private fun runResolvedProbeSubprocess(
	command: List<String>,
	libraryPath: String,
	timeoutSeconds: Long,
): Pkcs11SubprocessResult {
	val logger = KotlinLogging.logger {}

	logger.debug { "Spawning PKCS#11 subprocess: ${command.first()}, library=$libraryPath" }

	val process = ProcessBuilder(command).start()
	val pid = process.pid()
	logger.debug { "PKCS#11 subprocess pid=$pid started for '$libraryPath'" }

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
		return Pkcs11SubprocessResult.Success(pid, stdout)
	} finally {
		if (process.isAlive) {
			logger.debug { "Destroying leaked subprocess pid=$pid for '$libraryPath'" }
			process.destroyForcibly()
		}
	}
}

