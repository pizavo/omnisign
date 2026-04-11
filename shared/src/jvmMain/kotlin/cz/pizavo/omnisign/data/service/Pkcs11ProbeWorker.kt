package cz.pizavo.omnisign.data.service

import kotlin.system.exitProcess

/**
 * Standalone entry point for out-of-process PKCS#11 library probing.
 *
 * Invoked as a subprocess by [probeTokenIdentitiesViaSubprocess] to isolate native library
 * crashes (SIGSEGV, SIGABRT) from the host JVM. Some PKCS#11 middleware — notably SafeNet
 * eToken's `libeTPKCS15.so` — can crash with a NULL-pointer dereference inside `C_Initialize`
 * when no smart card reader or token is present. Running the probe in a child process
 * confines such crashes to the subprocess; the host application continues normally.
 *
 * Each invocation probes a single PKCS#11 library path passed as the sole command-line
 * argument and writes discovered token identities to stdout as tab-separated
 * `label\tserialNumber` lines.
 *
 * Exit codes:
 * - `0` — probing completed successfully (output may still be empty if no tokens are present).
 * - `1` — no library path argument was supplied.
 * - Non-zero / signal — the native library caused a fatal error; the host process treats
 *   this as an empty result set.
 */
object Pkcs11ProbeWorker {

    /**
     * Probe the PKCS#11 library at the path given as the first command-line argument and
     * print each discovered token identity as a `label\tserialNumber` line to stdout.
     *
     * @param args Single-element array containing the absolute path to the PKCS#11 library.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
	        exitProcess(1)
        }
        val identities = probeTokenIdentities(args[0])
        for (identity in identities) {
            println("${identity.label}\t${identity.serialNumber}")
        }
    }
}

