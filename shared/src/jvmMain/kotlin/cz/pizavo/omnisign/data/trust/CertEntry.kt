package cz.pizavo.omnisign.data.trust

import kotlinx.serialization.Serializable

/**
 * Cached metadata for one stored certificate, keyed by fingerprint in the trust index.
 *
 * The validity and subject fields are a rebuildable cache (re-derivable from the DER file) so
 * listing never has to parse the files. [sources] records import provenance - the origin paths a
 * certificate was imported from (or `"inline"`) - so a path reference can still resolve to its
 * stored copy after the original source file is gone.
 *
 * @property subjectDN The certificate subject distinguished name.
 * @property notBefore Start of validity, epoch milliseconds.
 * @property notAfter End of validity, epoch milliseconds.
 * @property sources Import origins (paths, or `"inline"`).
 */
@Serializable
internal data class CertEntry(
	val subjectDN: String,
	val notBefore: Long,
	val notAfter: Long,
	val sources: List<String> = emptyList(),
)
