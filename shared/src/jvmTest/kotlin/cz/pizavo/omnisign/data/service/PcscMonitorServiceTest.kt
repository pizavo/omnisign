package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import javax.smartcardio.ATR
import javax.smartcardio.Card
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CardTerminals

/**
 * Verifies [PcscMonitorService.currentReaders] tolerates failures gracefully, shapes the
 * snapshots correctly, and recovers from the JDK stale-PC/SC-context defect via
 * [PcscContextRecovery].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcscMonitorServiceTest : FunSpec({

	afterEach { unmockkAll() }

	test("currentReaders returns empty list when terminals provider returns null") {
		val service = PcscMonitorService(terminalsProvider = { null })
		service.currentReaders().shouldBeEmpty()
	}

	test("currentReaders returns empty list when terminals provider throws") {
		val service = PcscMonitorService(terminalsProvider = { error("pcscd unreachable") })
		service.currentReaders().shouldBeEmpty()
	}

	test("currentReaders returns empty when a stale context cannot be reset") {
		val terminals = mockk<CardTerminals>()
		every { terminals.list() } throws CardException("SCARD_E_NO_SERVICE")
		val recovery = mockk<PcscContextRecovery>()
		every { recovery.isStaleContext(any()) } returns true
		every { recovery.resetContext() } returns false

		val service = PcscMonitorService(terminalsProvider = { terminals }, recovery = recovery)

		service.currentReaders().shouldBeEmpty()
		verify(exactly = 1) { recovery.resetContext() }
		verify(exactly = 1) { terminals.list() }
	}

	test("currentReaders recovers and re-enumerates after a stale context is reset") {
		val reader = mockk<CardTerminal>()
		every { reader.name } returns "Recovered Reader"
		every { reader.isCardPresent } returns false

		val terminals = mockk<CardTerminals>()
		var calls = 0
		every { terminals.list() } answers {
			calls++
			if (calls == 1) throw CardException("SCARD_E_SERVICE_STOPPED")
			listOf(reader)
		}
		val recovery = mockk<PcscContextRecovery>()
		every { recovery.isStaleContext(any()) } returns true
		every { recovery.resetContext() } returns true

		val service = PcscMonitorService(terminalsProvider = { terminals }, recovery = recovery)

		val readers = service.currentReaders()

		readers shouldHaveSize 1
		readers[0] shouldBe PcscReader(name = "Recovered Reader", cardPresent = false, atrHex = null)
		verify(exactly = 1) { recovery.resetContext() }
		verify(exactly = 2) { terminals.list() }
	}

	test("currentReaders returns empty when enumeration still fails after a reset") {
		val terminals = mockk<CardTerminals>()
		every { terminals.list() } throws CardException("SCARD_E_SERVICE_STOPPED")
		val recovery = mockk<PcscContextRecovery>()
		every { recovery.isStaleContext(any()) } returns true
		every { recovery.resetContext() } returns true

		val service = PcscMonitorService(terminalsProvider = { terminals }, recovery = recovery)

		service.currentReaders().shouldBeEmpty()
		verify(exactly = 1) { recovery.resetContext() }
		verify(exactly = 2) { terminals.list() }
	}

	test("currentReaders maps each terminal to a PcscReader snapshot") {
		val emptyReader = mockk<CardTerminal>()
		every { emptyReader.name } returns "Empty Reader"
		every { emptyReader.isCardPresent } returns false

		val cardReader = mockk<CardTerminal>()
		val card = mockk<Card>()
		val atr = ATR(byteArrayOf(0x3B, 0x66.toByte(), 0x00))
		every { cardReader.name } returns "Card Reader"
		every { cardReader.isCardPresent } returns true
		every { cardReader.connect("*") } returns card
		every { card.atr } returns atr
		every { card.disconnect(false) } returns Unit

		val terminals = mockk<CardTerminals>()
		every { terminals.list() } returns listOf(emptyReader, cardReader)

		val service = PcscMonitorService(terminalsProvider = { terminals })

		val readers = service.currentReaders()

		readers shouldHaveSize 2
		readers[0] shouldBe PcscReader(name = "Empty Reader", cardPresent = false, atrHex = null)
		readers[1] shouldBe PcscReader(name = "Card Reader", cardPresent = true, atrHex = "3B6600")
	}

	test("currentReaders tolerates ATR read failure and reports cardPresent without ATR") {
		val reader = mockk<CardTerminal>()
		every { reader.name } returns "Mute Card Reader"
		every { reader.isCardPresent } returns true
		every { reader.connect("*") } throws CardException("Mute card")

		val terminals = mockk<CardTerminals>()
		every { terminals.list() } returns listOf(reader)

		val service = PcscMonitorService(terminalsProvider = { terminals })

		val readers = service.currentReaders()

		readers shouldHaveSize 1
		readers[0] shouldBe PcscReader(name = "Mute Card Reader", cardPresent = true, atrHex = null)
	}

	test("watcher polls on SCARD_E_NO_READERS_AVAILABLE and emits ReaderConnected when a reader appears later") {
		runTest {
			val reader = mockk<CardTerminal>()
			every { reader.name } returns "Late Reader"
			every { reader.isCardPresent } returns false

			val readerPresent = AtomicBoolean(false)
			val terminals = mockk<CardTerminals>()
			every { terminals.list() } answers { if (readerPresent.get()) listOf(reader) else emptyList() }
			every { terminals.waitForChange(any()) } throws
				CardException("waitForChange() failed", RuntimeException("SCARD_E_NO_READERS_AVAILABLE"))

			val recovery = mockk<PcscContextRecovery>()
			every { recovery.isStaleContext(any()) } returns false
			every { recovery.causeChainContains(any(), any()) } returns true

			val service = PcscMonitorService(
				terminalsProvider = { terminals },
				recovery = recovery,
				scope = backgroundScope,
			)

			val events = mutableListOf<PcscEvent>()
			backgroundScope.launch { service.events.collect { events += it } }

			testScheduler.advanceTimeBy(10_000)
			testScheduler.runCurrent()
			events.shouldBeEmpty()

			readerPresent.set(true)
			testScheduler.advanceTimeBy(10_000)
			testScheduler.runCurrent()

			events.any { it is PcscEvent.ReaderConnected } shouldBe true
		}
	}

	test("stale-context reset re-emits a card change that occurred during the stale window") {
		runTest {
			val cardPresent = AtomicBoolean(false)
			val reader = mockk<CardTerminal>()
			every { reader.name } returns "SafeNet Token JC 0"
			every { reader.isCardPresent } answers { cardPresent.get() }
			every { reader.connect("*") } throws CardException("mute")

			val terminals = mockk<CardTerminals>()
			every { terminals.list() } returns listOf(reader)
			every { terminals.waitForChange(any()) } throws
				CardException("waitForChange() failed", RuntimeException("SCARD_E_SERVICE_STOPPED"))

			val recovery = mockk<PcscContextRecovery>()
			every { recovery.isStaleContext(any()) } returns true
			every { recovery.causeChainContains(any(), any()) } returns false
			every { recovery.resetContext() } returns true

			val service = PcscMonitorService(
				terminalsProvider = { terminals },
				recovery = recovery,
				scope = backgroundScope,
			)

			val events = mutableListOf<PcscEvent>()
			backgroundScope.launch { service.events.collect { events += it } }

			testScheduler.runCurrent()
			cardPresent.set(true)
			testScheduler.advanceTimeBy(10_000)
			testScheduler.runCurrent()

			events.any { it is PcscEvent.CardInserted } shouldBe true
		}
	}
})
