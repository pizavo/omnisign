package cz.pizavo.omnisign.domain.model.signature

/**
 * Certificate information.
 */
data class CertificateInfo(
    val subjectDN: String,
    val issuerDN: String,
    val serialNumber: String,
    val validFrom: String,
    val validTo: String,
    val keyUsages: List<String> = emptyList(),
    val isQualified: Boolean = false
)

