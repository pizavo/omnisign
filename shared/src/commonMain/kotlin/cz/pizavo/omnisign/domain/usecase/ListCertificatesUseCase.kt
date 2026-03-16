package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
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
 *
 * Per-token warnings from the repository are preserved in the returned [CertificateDiscoveryResult]
 * so callers can explain to the user why expected certificates may be absent.
 */
class ListCertificatesUseCase(
    private val signingRepository: SigningRepository
) {
    /**
     * Return certificates available for signing from all discovered token sources.
     *
     * @return Discovery result with filtered certificates and any per-token access warnings,
     *         or a hard error when token discovery itself fails.
     */
    suspend operator fun invoke(): OperationResult<CertificateDiscoveryResult> =
        signingRepository.listAvailableCertificates().map { result ->
            result.copy(certificates = result.certificates.filter { it.isSigningCapable() })
        }

    private fun AvailableCertificateInfo.isSigningCapable(): Boolean =
        if (keyUsages.isNotEmpty()) {
            keyUsages.any { it == "digitalSignature" || it == "nonRepudiation" }
        } else {
            subjectDN != issuerDN
        }
}

