package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

/**
 * Parameters for signing operation.
 */
data class SigningParameters(
    val inputFile: String,
    val outputFile: String,
    val certificateAlias: String? = null,
    val hashAlgorithm: HashAlgorithm? = null,
    val signatureLevel: SignatureLevel? = null,
    val reason: String? = null,
    val location: String? = null,
    val contactInfo: String? = null,
    val addTimestamp: Boolean = true,
    val visibleSignature: VisibleSignatureParameters? = null
)

