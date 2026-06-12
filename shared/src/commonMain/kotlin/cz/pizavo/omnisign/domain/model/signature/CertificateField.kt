package cz.pizavo.omnisign.domain.model.signature

import kotlinx.serialization.Serializable

/**
 * A single name/value entry within a [CertificateDetailSection] — one parsed field of an X.509
 * certificate (a distinguished-name component, an extension, a validity bound, …).
 *
 * Both members are already rendered as display strings, so the value may span multiple lines (e.g.
 * an ASN.1 dump of a non-standard extension). Non-standard distinguished-name attributes and
 * unrecognised extensions are kept with their dotted OID as the [label], so nothing the certificate
 * carries is dropped.
 *
 * @property label Human-readable field name, or the dotted OID when the field is non-standard.
 * @property value The field's rendered value.
 */
@Serializable
data class CertificateField(
    val label: String,
    val value: String,
)
