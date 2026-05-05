package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import javax.smartcardio.ATR
import javax.smartcardio.Card
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CardTerminals

/**
 * Verifies [PcscMonitorService.currentReaders] tolerates failures gracefully and shapes
 * the snapshots correctly.
 */
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

	test("currentReaders returns empty list when terminal enumeration throws") {
		val terminals = mockk<CardTerminals>()
		every { terminals.list() } throws CardException("SCARD_E_NO_SERVICE")

		val service = PcscMonitorService(terminalsProvider = { terminals })

		service.currentReaders().shouldBeEmpty()
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
})
