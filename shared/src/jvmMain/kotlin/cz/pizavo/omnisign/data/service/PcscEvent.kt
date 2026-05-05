package cz.pizavo.omnisign.data.service

/**
 * Smart-card reader state-change event emitted by [PcscMonitorService.events].
 *
 * Subscribers can use these to drive UI refreshes (e.g., re-discover tokens when a card
 * is inserted) or future cache-invalidation logic for cached PKCS#11 sessions.  Every
 * event carries enough context to act on it without re-querying the monitor service.
 */
sealed interface PcscEvent {

	/**
	 * A new reader appeared on the system (e.g., a USB smart-card reader was just plugged in).
	 *
	 * @property name The newly-visible reader's name.
	 */
	data class ReaderConnected(val name: String) : PcscEvent

	/**
	 * A previously-known reader was removed (e.g., a USB reader unplugged).
	 *
	 * @property name The reader that disappeared.
	 */
	data class ReaderDisconnected(val name: String) : PcscEvent

	/**
	 * A card was inserted into a reader.
	 *
	 * @property reader Snapshot of the reader as observed when the card insertion was detected,
	 *   including its [PcscReader.atrHex] when readable.
	 */
	data class CardInserted(val reader: PcscReader) : PcscEvent

	/**
	 * A card was removed from a reader.
	 *
	 * @property reader Snapshot of the reader at the moment of removal; [PcscReader.cardPresent]
	 *   is `false` and [PcscReader.atrHex] is `null` for these events.
	 */
	data class CardRemoved(val reader: PcscReader) : PcscEvent
}
