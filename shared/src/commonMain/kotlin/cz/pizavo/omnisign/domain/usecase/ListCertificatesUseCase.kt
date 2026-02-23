package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.CertificateInfo
import cz.pizavo.omnisign.domain.repository.SigningRepository

/**
 * Use case for listing certificates suitable for document signing.
 *
 * A certificate qualifies when at least one of these conditions holds:
 * - Its X.509 key usage extension contains `digitalSignature` or `nonRepudiation`.
 * - Its key usage extension is absent **and** the certificate is not self-signed
 *   (i.e. subject DN differs from issuer DN).  This handles tokens such as the Windows MY
 *   store whose MSCAPI bridge does not expose key usage bits; self-signed device/development
 *   certificates are excluded by this rule.
 */
class ListCertificatesUseCase(
    private val signingRepository: SigningRepository
) {
    /**
     * Return certificates available for signing from all configured token sources.
     *
     * @return Filtered list of signing-capable certificate information, or an error.
     */
    suspend operator fun invoke(): OperationResult<List<CertificateInfo>> =
        signingRepository.listAvailableCertificates().map { certificates ->
            certificates.filter { it.isSigningCapable() }
        }

    private fun CertificateInfo.isSigningCapable(): Boolean =
        if (keyUsages.isNotEmpty()) {
            keyUsages.any { it == "digitalSignature" || it == "nonRepudiation" }
        } else {
            subjectDN != issuerDN
        }
}

