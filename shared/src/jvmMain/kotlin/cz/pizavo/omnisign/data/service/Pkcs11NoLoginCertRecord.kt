package cz.pizavo.omnisign.data.service

/**
 * One certificate object read from a token **without** `C_Login`, as produced inside a
 * [Pkcs11ProbeWorker] subprocess and serialised to its stdout.
 *
 * The fields are kept transport-friendly (no parsed X.509 in the subprocess): the DER and
 * label are Base64 so they survive a single tab-separated stdout line, and the parent
 * process ([parseProbeCertificates]) does the X.509 parsing where a crash is harmless.
 *
 * @property slotId Slot the certificate was found in.
 * @property ckaIdHex Lower-case hex of the object's `CKA_ID` (empty when absent).  This is
 *   the value a private key shares with its certificate, so it is the join key a future
 *   no-login-list → logged-in-sign handoff would rely on.
 * @property labelBase64 Base64 of the raw `CKA_LABEL` bytes (empty when absent).
 * @property derBase64 Base64 of the DER-encoded certificate (`CKA_VALUE`).
 */
internal data class Pkcs11NoLoginCertRecord(
	val slotId: Long,
	val ckaIdHex: String,
	val labelBase64: String,
	val derBase64: String,
)
