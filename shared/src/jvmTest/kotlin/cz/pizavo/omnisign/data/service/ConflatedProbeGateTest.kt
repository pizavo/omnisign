package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Verifies [ConflatedProbeGate] concurrency semantics: at most one block execution at a
 * time, stale results discarded when a pending request exists, and all coalesced callers
 * receiving the freshest result.
 */
class ConflatedProbeGateTest : FunSpec({

	test("single caller runs uncontested and receives its own result") {
		val gate = ConflatedProbeGate<String>()

		val result = gate.runOrCoalesce { "only-run" }

		result shouldBe "only-run"
	}

	test("two overlapping callers both receive the second probe result") {
		runTest {
			val gate = ConflatedProbeGate<Int>()
			var runCount = 0
			val firstProbeStarted = CompletableDeferred<Unit>()
			val allowFirstProbeFinish = CompletableDeferred<Unit>()

			val block: suspend () -> Int = {
				runCount++
				val thisRun = runCount
				if (thisRun == 1) {
					firstProbeStarted.complete(Unit)
					allowFirstProbeFinish.await()
				}
				thisRun
			}

			val callerA = async { gate.runOrCoalesce(block) }

			firstProbeStarted.await()

			val callerB = async { gate.runOrCoalesce(block) }

			delay(50.milliseconds)
			allowFirstProbeFinish.complete(Unit)

			val resultA = callerA.await()
			val resultB = callerB.await()

			runCount shouldBe 2
			resultA shouldBe 2
			resultB shouldBe 2
		}
	}

	test("three overlapping callers all receive the second probe result when B and C arrive during first run") {
		runTest {
			val gate = ConflatedProbeGate<Int>()
			var runCount = 0
			val firstProbeStarted = CompletableDeferred<Unit>()
			val allowFirstProbeFinish = CompletableDeferred<Unit>()

			val block: suspend () -> Int = {
				runCount++
				val thisRun = runCount
				if (thisRun == 1) {
					firstProbeStarted.complete(Unit)
					allowFirstProbeFinish.await()
				}
				thisRun
			}

			val callerA = async { gate.runOrCoalesce(block) }

			firstProbeStarted.await()

			val callerB = async { gate.runOrCoalesce(block) }
			val callerC = async { gate.runOrCoalesce(block) }

			delay(50.milliseconds)
			allowFirstProbeFinish.complete(Unit)

			val resultA = callerA.await()
			val resultB = callerB.await()
			val resultC = callerC.await()

			runCount shouldBe 2
			resultA shouldBe 2
			resultB shouldBe 2
			resultC shouldBe 2
		}
	}

	test("caller arriving during re-run triggers a third probe and all callers get third result") {
		runTest {
			val gate = ConflatedProbeGate<Int>()
			var runCount = 0
			val firstProbeStarted = CompletableDeferred<Unit>()
			val allowFirstProbeFinish = CompletableDeferred<Unit>()
			val secondProbeStarted = CompletableDeferred<Unit>()
			val allowSecondProbeFinish = CompletableDeferred<Unit>()

			val block: suspend () -> Int = {
				runCount++
				val thisRun = runCount
				when (thisRun) {
					1 -> {
						firstProbeStarted.complete(Unit)
						allowFirstProbeFinish.await()
					}

					2 -> {
						secondProbeStarted.complete(Unit)
						allowSecondProbeFinish.await()
					}
				}
				thisRun
			}

			val callerA = async { gate.runOrCoalesce(block) }
			firstProbeStarted.await()

			val callerB = async { gate.runOrCoalesce(block) }
			delay(50.milliseconds)
			allowFirstProbeFinish.complete(Unit)

			secondProbeStarted.await()

			val callerD = async { gate.runOrCoalesce(block) }
			delay(50.milliseconds)
			allowSecondProbeFinish.complete(Unit)

			val resultA = callerA.await()
			val resultB = callerB.await()
			val resultD = callerD.await()

			runCount shouldBe 3
			resultA shouldBe 3
			resultB shouldBe 3
			resultD shouldBe 3
		}
	}

	test("error in block propagates to leader and all coalesced waiters") {
		runTest {
			val gate = ConflatedProbeGate<String>()
			val firstProbeStarted = CompletableDeferred<Unit>()
			val allowFirstProbeFinish = CompletableDeferred<Unit>()

			val block: suspend () -> String = {
				firstProbeStarted.complete(Unit)
				allowFirstProbeFinish.await()
				error("probe failed")
			}

			val callerA = async {
				runCatching { gate.runOrCoalesce(block) }
			}

			firstProbeStarted.await()

			val callerB = async {
				runCatching { gate.runOrCoalesce(block) }
			}

			delay(50.milliseconds)
			allowFirstProbeFinish.complete(Unit)

			val resultA = callerA.await()
			val resultB = callerB.await()

			resultA.isFailure shouldBe true
			resultA.exceptionOrNull()!!.message shouldBe "probe failed"
			resultB.isFailure shouldBe true
			resultB.exceptionOrNull()!!.message shouldBe "probe failed"
		}
	}

	test("gate returns to idle after completion so next caller becomes a new leader") {
		runTest {
			val gate = ConflatedProbeGate<String>()

			val first = gate.runOrCoalesce { "first" }
			first shouldBe "first"

			val second = gate.runOrCoalesce { "second" }
			second shouldBe "second"
		}
	}

	test("gate returns to idle after error so next caller becomes a new leader") {
		runTest {
			val gate = ConflatedProbeGate<String>()

			val failed = runCatching { gate.runOrCoalesce { error("boom") } }
			failed.isFailure shouldBe true

			val recovered = gate.runOrCoalesce { "recovered" }
			recovered shouldBe "recovered"
		}
	}

	test("gate returns to idle after cancellation so next caller becomes a new leader") {
		runTest {
			val gate = ConflatedProbeGate<String>()
			val probeStarted = CompletableDeferred<Unit>()

			val job = launch {
				gate.runOrCoalesce {
					probeStarted.complete(Unit)
					delay(Duration.INFINITE)
					"never"
				}
			}

			probeStarted.await()
			job.cancel()
			job.join()

			val recovered = gate.runOrCoalesce { "after-cancel" }
			recovered shouldBe "after-cancel"
		}
	}

	test("leader cancellation delivers LeaderCancelledException to waiters, not CancellationException") {
		runTest {
			val gate = ConflatedProbeGate<String>()
			val probeStarted = CompletableDeferred<Unit>()
			val waiterReady = CompletableDeferred<Unit>()

			val leaderJob = launch {
				gate.runOrCoalesce {
					probeStarted.complete(Unit)
					waiterReady.await()
					delay(Duration.INFINITE)
					"never"
				}
			}

			probeStarted.await()

			val waiterResult = async {
				runCatching { gate.runOrCoalesce { "waiter-result" } }
			}

			delay(50.milliseconds)
			waiterReady.complete(Unit)
			delay(50.milliseconds)
			leaderJob.cancel()
			leaderJob.join()

			val result = waiterResult.await()
			result.isFailure shouldBe true
			result.exceptionOrNull().shouldBeInstanceOf<LeaderCancelledException>()
		}
	}

	test("waiter cancellation does not affect the leader or other waiters") {
		runTest {
			val gate = ConflatedProbeGate<String>()
			val probeStarted = CompletableDeferred<Unit>()
			val allowProbeFinish = CompletableDeferred<Unit>()

			val leader = async {
				gate.runOrCoalesce {
					probeStarted.complete(Unit)
					allowProbeFinish.await()
					"leader-result"
				}
			}

			probeStarted.await()

			val waiterToCancel = launch {
				gate.runOrCoalesce { "waiter-never" }
			}

			delay(50.milliseconds)
			waiterToCancel.cancel()
			waiterToCancel.join()

			allowProbeFinish.complete(Unit)

			val result = leader.await()
			result shouldBe "leader-result"
		}
	}
})



