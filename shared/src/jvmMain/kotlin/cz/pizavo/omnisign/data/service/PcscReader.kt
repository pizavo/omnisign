package cz.pizavo.omnisign.data.service

/**
 * Snapshot of one connected PC/SC smart-card reader at a moment in time.
 *
 * Produced by [PcscMonitorService.currentReaders] for diagnostics and embedded in
 * [PcscEvent] payloads emitted by the event flow.  Pure data; no live link to the
 * underlying `javax.smartcardio.CardTerminal`.
 *
 * @property name Human-readable reader name as reported by the PC/SC subsystem
 *   (e.g., `"AlcorMicro USB Smart Card Reader 0"`).  Stable across enumerations as long
 *   as the reader is connected.
 * @property cardPresent `true` when a smart card is currently inserted in the reader.
 * @property atrHex Card Answer-To-Reset bytes as an upper-case hex string when a card
 *   is present and its ATR can be read; `null` when [cardPresent] is `false` or the ATR
 *   is unavailable (e.g., the card is in mute state).
 */
data class PcscReader(
	val name: String,
	val cardPresent: Boolean,
	val atrHex: String?,
)
