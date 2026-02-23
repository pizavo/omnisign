package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

/**
 * Parameters for signing operation.
 *
 * @property inputFile Absolute path to the PDF file to sign.
 * @property outputFile Absolute path for the signed output file.
 * @property certificateAlias Alias identifying which certificate to use; null selects the first available.
 * @property hashAlgorithm Hash algorithm for the signature digest; falls back to the resolved config default.
 * @property signatureLevel PAdES level for the signature; falls back to the resolved config default.
 * @property reason Optional reason for signing embedded in the PDF signature dictionary.
 * @property location Optional signing location embedded in the PDF signature dictionary.
 * @property contactInfo Optional contact information embedded in the PDF signature dictionary.
 * @property addTimestamp Whether to include an RFC 3161 timestamp in the signature.
 * @property visibleSignature Optional visible signature appearance parameters.
 * @property resolvedConfig Full resolved configuration providing timestamp server, OCSP/CRL settings, etc.
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
    val visibleSignature: VisibleSignatureParameters? = null,
    val resolvedConfig: ResolvedConfig? = null
)

