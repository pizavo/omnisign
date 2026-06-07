package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies [CountingExecutorService] counts only member-state (`TLAnalysis`) tasks and that it
 * still runs every task on the delegate pool.
 */
class CountingExecutorServiceTest : FunSpec({

	// Local class whose simple name matches the DSS member-state task the counter looks for.
	class TLAnalysis(private val body: () -> Unit = {}) : Runnable {
		override fun run() = body()
	}

	// Any other analysis task — must be ignored by the counter but still executed.
	class LOTLAnalysis(private val body: () -> Unit = {}) : Runnable {
		override fun run() = body()
	}

	test("counts only TLAnalysis tasks, ignoring others, and reports submitted/completed") {
		val delegate = Executors.newSingleThreadExecutor()
		try {
			val last = AtomicReference(0 to 0)
			var ranOther = false
			val counting = CountingExecutorService(delegate) { submitted, completed ->
				last.set(submitted to completed)
			}

			counting.execute(LOTLAnalysis { ranOther = true })
			counting.execute(TLAnalysis())
			counting.execute(TLAnalysis())

			delegate.shutdown()
			delegate.awaitTermination(5, TimeUnit.SECONDS) shouldBe true

			last.get() shouldBe (2 to 2)
			ranOther shouldBe true
		} finally {
			delegate.shutdownNow()
		}
	}

	test("reset zeroes the counters") {
		val delegate = Executors.newSingleThreadExecutor()
		try {
			val last = AtomicReference(-1 to -1)
			val counting = CountingExecutorService(delegate) { submitted, completed ->
				last.set(submitted to completed)
			}

			counting.execute(TLAnalysis())
			delegate.shutdown()
			delegate.awaitTermination(5, TimeUnit.SECONDS) shouldBe true

			counting.reset()
			last.get() shouldBe (0 to 0)
		} finally {
			delegate.shutdownNow()
		}
	}
})
