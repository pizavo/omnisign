package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.core.spec.style.FunSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File

/**
 * Verifies the event-type → cache-invalidation policy and the proactive-rediscovery
 * launch implemented by [Pkcs11CacheInvalidator.handleEvent].
 *
 * The tests drive [Pkcs11CacheInvalidator.handleEvent] directly rather than going
 * through the [PcscMonitorService.events] flow so the assertions stay deterministic
 * and don't depend on shared-flow buffering.  The launched rediscovery coroutine is
 * advanced via the injected [TestScope]'s scheduler so we can assert
 * [Pkcs11Discoverer.discoverTokens] was called with the expected arguments.
 */
class Pkcs11CacheInvalidatorTest : FunSpec({

	val dropDir = File("/tmp/test-pkcs11")
	val userLibs = listOf(CustomPkcs11Library(name = "Test", path = "/tmp/test.so"))
	val config = AppConfig(global = GlobalConfig(customPkcs11Libraries = userLibs))

	/**
	 * Build a [Pkcs11CacheInvalidator] backed by an inert [PcscMonitorService] mock
	 * whose `events` flow never emits, plus a [Pkcs11Discoverer] mock and a
	 * [ConfigRepository] mock returning [config].  Returns the invalidator, the
	 * discoverer, and the [TestScope] tied to the caller's `runTest` block — the
	 * caller advances it with `runCurrent()` to drive the proactive rediscovery
	 * coroutine to completion.
	 */
	fun TestScope.newFixture(
		candidateCacheIsCardDependent: Boolean = false,
	): Triple<Pkcs11CacheInvalidator, Pkcs11Discoverer, ConfigRepository> {
		val monitor = mockk<PcscMonitorService>()
		val emptyFlow = MutableSharedFlow<PcscEvent>().asSharedFlow()
		every { monitor.events } returns emptyFlow

		val discoverer = mockk<Pkcs11Discoverer>(relaxUnitFun = true)
		coEvery { discoverer.discoverTokens(any(), any()) } returns emptyList()

		val configRepository = mockk<ConfigRepository>()
		coEvery { configRepository.getCurrentConfig() } returns config

		// `backgroundScope` is auto-cancelled at the end of `runTest`, so the
		// indefinitely-suspending `events.collect` collector coroutine doesn't trip
		// `UncompletedCoroutinesError`.  The launched rediscovery coroutines run on
		// the same dispatcher as the test, so `testScheduler.runCurrent()` still
		// drives them to completion within the test block.
		val invalidator = Pkcs11CacheInvalidator(
			monitor = monitor,
			discoverer = discoverer,
			configRepository = configRepository,
			appDataPkcs11Dir = dropDir,
			scope = backgroundScope,
			candidateCacheIsCardDependent = candidateCacheIsCardDependent,
		)
		return Triple(invalidator, discoverer, configRepository)
	}

	test("CardInserted invalidates only the probe cache on a card-independent platform") {
		runTest {
			val (invalidator, discoverer, _) = newFixture()
			val reader = PcscReader(name = "Reader 1", cardPresent = true, atrHex = "3B...")

			invalidator.handleEvent(PcscEvent.CardInserted(reader))
			testScheduler.runCurrent()

			verify(exactly = 1) { discoverer.invalidateProbes() }
			verify(exactly = 0) { discoverer.invalidateCandidates() }
			coVerify(exactly = 1) {
				discoverer.discoverTokens(
					appDataPkcs11Dir = dropDir,
					userPkcs11Libraries = listOf("Test" to "/tmp/test.so"),
				)
			}
		}
	}

	test("CardRemoved invalidates only the probe cache on a card-independent platform") {
		runTest {
			val (invalidator, discoverer, _) = newFixture()
			val reader = PcscReader(name = "Reader 1", cardPresent = false, atrHex = null)

			invalidator.handleEvent(PcscEvent.CardRemoved(reader))
			testScheduler.runCurrent()

			verify(exactly = 1) { discoverer.invalidateProbes() }
			verify(exactly = 0) { discoverer.invalidateCandidates() }
			coVerify(exactly = 1) { discoverer.discoverTokens(any(), any()) }
		}
	}

	test("CardInserted also invalidates the candidate cache when the candidate set is card-dependent") {
		runTest {
			val (invalidator, discoverer, _) = newFixture(candidateCacheIsCardDependent = true)
			val reader = PcscReader(name = "Reader 1", cardPresent = true, atrHex = "3B...")

			invalidator.handleEvent(PcscEvent.CardInserted(reader))
			testScheduler.runCurrent()

			verify(exactly = 1) { discoverer.invalidateProbes() }
			verify(exactly = 1) { discoverer.invalidateCandidates() }
			coVerify(exactly = 1) { discoverer.discoverTokens(any(), any()) }
		}
	}

	test("CardRemoved also invalidates the candidate cache when the candidate set is card-dependent") {
		runTest {
			val (invalidator, discoverer, _) = newFixture(candidateCacheIsCardDependent = true)
			val reader = PcscReader(name = "Reader 1", cardPresent = false, atrHex = null)

			invalidator.handleEvent(PcscEvent.CardRemoved(reader))
			testScheduler.runCurrent()

			verify(exactly = 1) { discoverer.invalidateProbes() }
			verify(exactly = 1) { discoverer.invalidateCandidates() }
			coVerify(exactly = 1) { discoverer.discoverTokens(any(), any()) }
		}
	}

	test("ReaderConnected invalidates both caches and triggers rediscovery") {
		runTest {
			val (invalidator, discoverer, _) = newFixture()

			invalidator.handleEvent(PcscEvent.ReaderConnected("New Reader"))
			testScheduler.runCurrent()

			verify(exactly = 1) { discoverer.invalidateProbes() }
			verify(exactly = 1) { discoverer.invalidateCandidates() }
			coVerify(exactly = 1) { discoverer.discoverTokens(any(), any()) }
		}
	}

	test("ReaderDisconnected invalidates both caches and triggers rediscovery") {
		runTest {
			val (invalidator, discoverer, _) = newFixture()

			invalidator.handleEvent(PcscEvent.ReaderDisconnected("Removed Reader"))
			testScheduler.runCurrent()

			verify(exactly = 1) { discoverer.invalidateProbes() }
			verify(exactly = 1) { discoverer.invalidateCandidates() }
			coVerify(exactly = 1) { discoverer.discoverTokens(any(), any()) }
		}
	}

	test("handleEvent returns immediately without awaiting the rediscovery") {
		runTest {
			val (invalidator, discoverer, _) = newFixture()

			invalidator.handleEvent(PcscEvent.CardInserted(PcscReader("Reader", true, null)))

			// Without runCurrent(), the launched coroutine has not progressed past its
			// first suspension point, so discoverTokens has not been called yet —
			// confirming the launch is fire-and-forget rather than blocking handleEvent.
			coVerify(exactly = 0) { discoverer.discoverTokens(any(), any()) }
		}
	}

	test("rediscovery re-reads user libraries from config on every event") {
		runTest {
			val (invalidator, discoverer, configRepository) = newFixture()

			invalidator.handleEvent(PcscEvent.CardInserted(PcscReader("R", true, null)))
			invalidator.handleEvent(PcscEvent.CardRemoved(PcscReader("R", false, null)))
			testScheduler.runCurrent()

			coVerify(exactly = 2) { configRepository.getCurrentConfig() }
			coVerify(exactly = 2) { discoverer.discoverTokens(any(), any()) }
		}
	}
})
