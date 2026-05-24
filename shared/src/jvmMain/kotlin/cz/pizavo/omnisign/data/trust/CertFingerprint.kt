package cz.pizavo.omnisign.data.trust

import java.security.MessageDigest

/**
 * Compute a certificate's algorithm-prefixed SHA-256 fingerprint (`sha256-<lowercase-hex>`) over
 * its DER encoding - the same value reported by `openssl x509 -fingerprint -sha256` and used as
 * the content-addressed filename stem.
 *
 * @param der The certificate's DER encoding.
 * @return The fingerprint, e.g. `sha256-1a2b...`.
 */
internal fun certFingerprint(der: ByteArray): String {
	val digest = MessageDigest.getInstance("SHA-256").digest(der)
	return "sha256-" + digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
