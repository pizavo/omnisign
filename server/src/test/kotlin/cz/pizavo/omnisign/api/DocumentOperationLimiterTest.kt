package cz.pizavo.omnisign.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that [DocumentOperationLimiter] runs operations off the caller's thread, never more of
 * them at once than it allows, and frees an operation's turn whether the operation returns or fails.
 */
class DocumentOperationLimiterTest : FunSpec({

	test("an operation runs off the thread of the call that started it") {
		val limiter = DocumentOperationLimiter(maxConcurrent = 1)
		Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { callThread ->
			withContext(callThread) {
				val caller = Thread.currentThread()
				limiter.execute { Thread.currentThread() } shouldNotBe caller
			}
		}
	}

	test("no more operations run at once than the limit, and the rest wait for a turn") {
		val limiter = DocumentOperationLimiter(maxConcurrent = LIMIT)
		val running = AtomicInteger()
		val mostRunning = AtomicInteger()
		val firstOnesStarted = CountDownLatch(LIMIT)
		val release = CountDownLatch(1)
		val finished = AtomicInteger()

		withTimeout(TIMEOUT_MILLIS) {
			coroutineScope {
				val operations = List(OPERATIONS) {
					async(Dispatchers.Default) {
						limiter.execute {
							mostRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
							firstOnesStarted.countDown()
							release.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
							running.decrementAndGet()
							finished.incrementAndGet()
						}
					}
				}
				firstOnesStarted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) shouldBe true
				Thread.sleep(SETTLE_MILLIS)
				running.get() shouldBe LIMIT
				release.countDown()
				operations.awaitAll()
			}
		}

		mostRunning.get() shouldBe LIMIT
		finished.get() shouldBe OPERATIONS
	}

	test("an operation that fails passes its exception on and frees its turn") {
		val limiter = DocumentOperationLimiter(maxConcurrent = 1)

		shouldThrow<IllegalStateException> {
			limiter.execute<Unit> { error("DSS failed") }
		}.message shouldBe "DSS failed"

		withTimeout(TIMEOUT_MILLIS) {
			limiter.execute { "next" } shouldBe "next"
		}
	}
}) {
	companion object {

		/** The limit of the limiter that the concurrency test drives. */
		private const val LIMIT = 2

		/** How many operations the concurrency test starts at once, more than [LIMIT]. */
		private const val OPERATIONS = 5

		/** How long a test waits for an operation before it fails. */
		private const val TIMEOUT_MILLIS = 10_000L

		/** How long the concurrency test gives a waiting operation to start, wrongly, before it checks. */
		private const val SETTLE_MILLIS = 200L
	}
}
