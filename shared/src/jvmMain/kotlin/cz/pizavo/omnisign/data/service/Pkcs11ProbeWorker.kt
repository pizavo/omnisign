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
 * Each invocation probes a single PKCS#11 library path passed as the first command-line
 * argument and writes discovered token identities to stdout as tab-separated
 * `label\tserialNumber\tslotId` lines.  The slot ID is included so the parent process can
 * pin the SunPKCS11 provider to the correct slot when constructing `Pkcs11SignatureToken`,
 * which is essential for aggregator modules such as p11-kit-proxy where slot 0 is rarely
 * the user-PIN-protected slot.
 *
 * When a second argument `--certs` is supplied (used only by the diagnostics sweep, never
 * by discovery or warmup), the worker additionally performs a no-`C_Login` certificate
 * enumeration and appends `CERT\t<slot>\t<ckaIdHex>\t<labelBase64>\t<derBase64>` lines
 * **after** the identity lines have been flushed.  The cert pass is best-effort and
 * isolated in a `runCatching`, so it can never suppress or corrupt the identity output
 * that discovery depends on.
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
     * print each discovered token identity as a `label\tserialNumber\tslotId` line to stdout.
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
            println("${identity.label}\t${identity.serialNumber}\t${identity.slotId}")
        }
        if (args.getOrNull(1) == "--certs") {
            System.out.flush()
            runCatching {
                for (cert in probeNoLoginCertificates(args[0])) {
                    println("CERT\t${cert.slotId}\t${cert.ckaIdHex}\t${cert.labelBase64}\t${cert.derBase64}")
                }
            }
        }
    }
}

