package cz.pizavo.omnisign.data.service

/**
 * Map a POSIX signal number to its conventional name for diagnostic logging.
 *
 * Shared by [Pkcs11SubprocessProber] and [Pkcs11WarmupService] when reporting a probe
 * subprocess that exited via a signal (exit code > 128).
 *
 * @param signal Signal number (e.g. 6 for SIGABRT, 11 for SIGSEGV).
 * @return Name such as `"SIGSEGV"`, or `"signal $signal"` for unmapped values.
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
