package cz.pizavo.omnisign.data.service

import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Child-process fixture for [Pkcs11SubprocessProberTest], standing in for a PKCS#11 worker.
 *
 * Spawned as a real subprocess so the prober's stream draining, sentinel detection and process
 * classification are exercised end to end.  The behaviours a mocked [Process] cannot reproduce
 * are the whole point: a child that delivers its output and then never exits models middleware
 * that deadlocks the process at native exit, where the parent sees neither an exit code nor EOF
 * on stdout.
 *
 * The mode is passed as the first argument:
 * - `sentinel-then-hang` — print two identity rows, print the sentinel, then block forever.
 * - `sentinel-then-exit` — print one identity row, print the sentinel, exit `0`.
 * - `no-sentinel-exit` — print one identity row and exit `0` without a sentinel.
 * - `no-sentinel-crash` — print a partial row and exit `3` without a sentinel.
 * - `silent-hang` — print nothing and block forever.
 */
object SubprocessProbeFixture {

	/**
	 * Emit the output for the mode named by the first argument, then exit or block per that mode.
	 *
	 * @param args Single-element array holding the mode name; an unknown mode exits `2`.
	 */
	@JvmStatic
	fun main(args: Array<String>) {
		when (args.firstOrNull()) {
			"sentinel-then-hang" -> {
				emit("First Token\tSN-001\t0")
				emit("Second Token\tSN-002\t1")
				emit(Pkcs11Prober.OUTPUT_TERMINATOR)
				CountDownLatch(1).await()
			}

			"sentinel-then-exit" -> {
				emit("Only Token\tSN-003\t0")
				emit(Pkcs11Prober.OUTPUT_TERMINATOR)
			}

			"no-sentinel-exit" -> emit("Legacy Token\tSN-004\t0")

			"no-sentinel-crash" -> {
				emit("Partial Token\tSN-005\t0")
				exitProcess(3)
			}

			"silent-hang" -> CountDownLatch(1).await()

			else -> exitProcess(2)
		}
	}

	/**
	 * Print [line] to stdout and flush, so the parent observes it before this process blocks.
	 */
	private fun emit(line: String) {
		println(line)
		System.out.flush()
	}
}
