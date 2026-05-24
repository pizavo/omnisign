package cz.pizavo.omnisign.data.archive

import kotlinx.serialization.Serializable

/**
 * Manifest bundled in a configuration export archive mapping trust scopes to the certificates they
 * reference. The certificate bytes live in `trusted-certs/<fingerprint>.der` sibling entries; this
 * manifest records which scope references each one and with what trust type, so an import can
 * replay [cz.pizavo.omnisign.domain.repository.TrustStore.add] per reference.
 *
 * @property version Manifest schema version, for forward compatibility.
 * @property entries Flat list of scope → certificate references.
 */
@Serializable
internal data class TrustArchiveManifest(
	val version: Int = 1,
	val entries: List<TrustArchiveEntry> = emptyList(),
)
