package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.CertificateInfo
import cz.pizavo.omnisign.domain.repository.SigningRepository

/**
 * Use case for listing available certificates.
 */
class ListCertificatesUseCase(
    private val signingRepository: SigningRepository
) {
    /**
     * List available certificates from configured sources.
     *
     * @return List of certificate information or error
     */
    suspend operator fun invoke(): OperationResult<List<CertificateInfo>> {
        return signingRepository.listAvailableCertificates()
    }
}


