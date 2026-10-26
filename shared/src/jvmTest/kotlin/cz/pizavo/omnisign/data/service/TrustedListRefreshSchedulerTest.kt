package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.data.repository.DssServiceFactory
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import java.io.FileNotFoundException
import kotlin.time.Clock

/**
 * Verifies [TrustedListRefreshScheduler.nextRefreshDelay]: healthy trust waits the full
 * configured interval, a source that failed to load is retried sooner, and a persistently
 * failing source falls back to the normal cadence after enough fast retries rather than
 * being hammered.
 *
 * Also verifies that [TrustedListRefreshScheduler.refreshNow] primes the sources before the hard
 * refresh, returns the sources the hard refresh could not load, and passes on an error that keeps
 * the refresh from running.
 */
class TrustedListRefreshSchedulerTest : FunSpec({

	val scheduler = TrustedListRefreshScheduler(mockk(relaxed = true), mockk(relaxed = true))
	val day = 24L * 60L * 60L * 1000L

	/** A scheduler over [factory] whose configuration enables nothing but the defaults. */
	fun schedulerOver(factory: DssServiceFactory): TrustedListRefreshScheduler {
		val configRepository = mockk<ConfigRepository>()
		coEvery { configRepository.getCurrentConfig() } returns AppConfig()
		return TrustedListRefreshScheduler(factory, configRepository)
	}

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

	test("refreshNow primes the sources and returns the ones the hard refresh could not load") {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		val failure = TrustedListRefreshFailure.CustomList("Company TL", Clock.System.now())
		every { factory.forceRefreshTrustedSources() } returns failure

		schedulerOver(factory).refreshNow() shouldBe failure

		verifyOrder {
			factory.warmUpTrustedSources(any(), any())
			factory.forceRefreshTrustedSources()
		}
	}

	test("refreshNow returns null when the hard refresh loaded every source") {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		every { factory.forceRefreshTrustedSources() } returns null

		schedulerOver(factory).refreshNow() shouldBe null
	}

	test("refreshNow passes on an error that keeps the refresh from running") {
		val factory = mockk<DssServiceFactory>(relaxed = true)
		every { factory.warmUpTrustedSources(any(), any()) } throws FileNotFoundException("signer.cer")

		shouldThrow<FileNotFoundException> { schedulerOver(factory).refreshNow() }
	}
})
