package cz.pizavo.omnisign.data.service

/**
 * Outcome of running a [Pkcs11ProbeWorker] subprocess to completion.
 *
 * Produced by [Pkcs11SubprocessProber].  Each caller interprets it differently:
 * - [Pkcs11WarmupService]: [Success] → log validation + prime the probe cache;
 *   [Crashed] → [Pkcs11CrashBlacklist.registerCrashed]; [TimedOut] → no record
 *   (retried on demand).
 * - [Pkcs11Prober.probeIdentities]: [Success] → parse [Success.stdout] for
 *   [Pkcs11TokenIdentity] rows; any failure → empty list.
 */
sealed interface Pkcs11SubprocessResult {

	/**
	 * The subprocess delivered a complete output payload — the library loaded and probed
	 * successfully.
	 *
	 * Either it printed [Pkcs11Prober.OUTPUT_TERMINATOR], after which [Pkcs11SubprocessProber]
	 * kills it without waiting for an exit that middleware deadlocked at native exit may never
	 * reach, or it reached EOF and exited with code 0.
	 *
	 * @property pid PID of the child process.
	 * @property stdout Full standard output captured from the subprocess, excluding the
	 *   terminator line.
	 * @property stderr Standard error captured from the subprocess, truncated by
	 *   [Pkcs11SubprocessProber] to keep log output bounded.  A worker that exits cleanly may
	 *   still report a non-fatal diagnostic here (e.g. `Pkcs11ModuleDiscoveryWorker` noting
	 *   that libp11-kit could not be loaded); defaults to empty.
	 */
	data class Success(val pid: Long, val stdout: String, val stderr: String = "") : Pkcs11SubprocessResult

	/**
	 * Subprocess exited with a non-zero code (native crash, probing error, etc.).
	 *
	 * @property pid PID of the child process.
	 * @property exitCode The process exit code (values > 128 typically indicate a signal).
	 * @property stderr Standard error output, truncated by [Pkcs11SubprocessProber] to keep
	 *   log output bounded when a crashed library dumps a large native stack trace.
	 */
	data class Crashed(val pid: Long, val exitCode: Int, val stderr: String) : Pkcs11SubprocessResult

	/**
	 * Subprocess did not complete within the allowed time and was forcibly killed.
	 *
	 * @property pid PID of the killed child process.
	 */
	data class TimedOut(val pid: Long) : Pkcs11SubprocessResult
}
