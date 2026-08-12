package cz.pizavo.omnisign.ui.viewmodel

import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import cz.pizavo.omnisign.domain.port.RenewalActivityProbe
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Unit tests for [RenewalStatusViewModel], covering when the settings badge is raised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenewalStatusViewModelTest : FunSpec({

	val testDispatcher = StandardTestDispatcher()
	val ranAt = Instant.fromEpochSeconds(1_700_000_000)

	beforeTest { Dispatchers.setMain(testDispatcher) }

	afterTest { Dispatchers.resetMain() }

	fun record(
		outcome: RenewalRunOutcome = RenewalRunOutcome.SUCCESS,
		failuresSinceSuccess: Int = 0,
		runStartedAt: Instant? = null,
	) = RenewalRunRecord(
		lastRunAt = ranAt,
		outcome = outcome,
		lastSuccessAt = ranAt,
		failuresSinceSuccess = failuresSinceSuccess,
		runStartedAt = runStartedAt,
	)

	fun viewModelFor(
		stored: RenewalRunRecord?,
		runInFlight: Boolean = false,
	): RenewalStatusViewModel {
		val store: RenewalRunRecordStore = mockk { every { load() } returns stored }
		val probe: RenewalActivityProbe = mockk { every { isRunInFlight() } returns runInFlight }
		return RenewalStatusViewModel(store, probe, testDispatcher)
	}

	test("a healthy last run raises no badge") {
		runTest(testDispatcher) {
			val vm = viewModelFor(record())
			vm.refresh()
			advanceUntilIdle()

			vm.needsAttention.value shouldBe false
		}
	}

	test("a failure since the last success raises the badge") {
		runTest(testDispatcher) {
			val vm = viewModelFor(record(RenewalRunOutcome.COMPLETED_WITH_ERRORS, failuresSinceSuccess = 1))
			vm.refresh()
			advanceUntilIdle()

			vm.needsAttention.value shouldBe true
		}
	}

	test("a run marker with no run executing raises the badge") {
		runTest(testDispatcher) {
			val vm = viewModelFor(record(runStartedAt = ranAt), runInFlight = false)
			vm.refresh()
			advanceUntilIdle()

			vm.needsAttention.value shouldBe true
		}
	}

	test("a run marker belonging to a run still executing raises no badge") {
		runTest(testDispatcher) {
			val vm = viewModelFor(record(runStartedAt = ranAt), runInFlight = true)
			vm.refresh()
			advanceUntilIdle()

			vm.needsAttention.value shouldBe false
		}
	}

	test("no recorded run at all raises no badge") {
		runTest(testDispatcher) {
			val vm = viewModelFor(null)
			vm.refresh()
			advanceUntilIdle()

			vm.needsAttention.value shouldBe false
		}
	}

	test("an unreadable record store raises no badge rather than failing") {
		runTest(testDispatcher) {
			val store: RenewalRunRecordStore = mockk { every { load() } throws IllegalStateException("boom") }
			val vm = RenewalStatusViewModel(store, null, testDispatcher)
			vm.refresh()
			advanceUntilIdle()

			vm.needsAttention.value shouldBe false
		}
	}

	test("no backend at all raises no badge") {
		runTest(testDispatcher) {
			val vm = RenewalStatusViewModel(null, null, testDispatcher)
			vm.refresh()
			advanceUntilIdle()

			vm.needsAttention.value shouldBe false
		}
	}
})
