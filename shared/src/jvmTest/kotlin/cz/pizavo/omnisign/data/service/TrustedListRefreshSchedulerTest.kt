package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

/**
 * Verifies [TrustedListRefreshScheduler.nextRefreshDelay]: healthy trust waits the full
 * configured interval, a source that failed to load is retried sooner, and a persistently
 * failing source falls back to the normal cadence after enough fast retries rather than
 * being hammered.
 */
class TrustedListRefreshSchedulerTest : FunSpec({

	val scheduler = TrustedListRefreshScheduler(mockk(relaxed = true), mockk(relaxed = true))
	val day = 24L * 60L * 60L * 1000L

	test("healthy trust waits the full configured interval") {
		scheduler.nextRefreshDelay(incompleteTrust = false, fastRetries = 0, configuredInterval = day) shouldBe day
	}

	test("incomplete trust retries sooner than the full interval") {
		val delay = scheduler.nextRefreshDelay(incompleteTrust = true, fastRetries = 0, configuredInterval = day)
		(delay in 1L until day) shouldBe true
	}

	test("a persistently failing source falls back to the full interval after enough retries") {
		scheduler.nextRefreshDelay(incompleteTrust = true, fastRetries = 100, configuredInterval = day) shouldBe day
	}

	test("the retry interval never exceeds the configured interval") {
		val oneMinute = 60L * 1000L
		scheduler.nextRefreshDelay(incompleteTrust = true, fastRetries = 0, configuredInterval = oneMinute) shouldBe oneMinute
	}
})
