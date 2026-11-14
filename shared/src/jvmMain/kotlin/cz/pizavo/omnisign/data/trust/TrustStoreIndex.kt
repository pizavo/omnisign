package cz.pizavo.omnisign.data.trust

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The machine-readable trust index persisted as `contents.cbor`.
 *
 * @property version The format version the index is written in. It is always written, so that a
 *   build can tell an index in a format it does not read, as a newer build may write one, from an
 *   index it does; builds that left it out wrote version 1, which an index without one reads as.
 *   [FileTrustStore] reads only [CURRENT_VERSION].
 * @property certs Stored certificate metadata, keyed by algorithm-prefixed SHA-256 fingerprint.
 * @property scopes References per scope key (`"global"` or `"profile:<name>"`); a certificate is
 *   shared across every scope that references it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class TrustStoreIndex(
	@EncodeDefault val version: Int = CURRENT_VERSION,
	val certs: Map<String, CertEntry> = emptyMap(),
	val scopes: Map<String, List<CertRef>> = emptyMap(),
) {
	companion object {
		/** The format version this build writes and the only one it reads. */
		const val CURRENT_VERSION = 1
	}
}
