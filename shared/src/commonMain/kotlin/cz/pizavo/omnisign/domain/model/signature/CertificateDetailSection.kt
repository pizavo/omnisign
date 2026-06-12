package cz.pizavo.omnisign.domain.model.signature

import kotlinx.serialization.Serializable

/**
 * A titled group of [CertificateField]s in the full-certificate view — e.g. "Subject", "Issuer",
 * "Validity", "Public Key", "Extensions", "Fingerprints".
 *
 * The sections together form a complete, faithful dump of an X.509 certificate (every
 * distinguished-name component and every extension, standard or not), parsed on the JVM from the
 * certificate's DER bytes and carried on each [CertificateChainLink.details] (one per certificate
 * in [CertificateInfo.chain]) so any target — including the web client receiving it from the
 * server — can render it without re-parsing.
 *
 * @property title Section heading.
 * @property fields Ordered fields belonging to this section.
 */
@Serializable
data class CertificateDetailSection(
    val title: String,
    val fields: List<CertificateField>,
)
