package cz.pizavo.omnisign.data.trust

import kotlinx.serialization.Serializable

/**
 * The machine-readable trust index persisted as `contents.cbor`.
 *
 * @property version Schema version, for forward migration.
 * @property certs Stored certificate metadata, keyed by algorithm-prefixed SHA-256 fingerprint.
 * @property scopes References per scope key (`"global"` or `"profile:<name>"`); a certificate is
 *   shared across every scope that references it.
 */
@Serializable
internal data class TrustStoreIndex(
	val version: Int = 1,
	val certs: Map<String, CertEntry> = emptyMap(),
	val scopes: Map<String, List<CertRef>> = emptyMap(),
)
