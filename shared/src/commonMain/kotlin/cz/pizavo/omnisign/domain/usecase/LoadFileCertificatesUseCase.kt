package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.SigningRepository

/**
 * Use case for loading certificates from a user-selected PKCS#12 file.
 *
 * Delegates to [SigningRepository.loadCertificatesFromFile], which prompts
 * the user for the file password and returns the certificates found in it.
 */
class LoadFileCertificatesUseCase(
    private val signingRepository: SigningRepository,
) {
    /**
     * Open a PKCS#12 file at [filePath], prompt for its password, and return signing-capable certificates.
     *
     * @param filePath Absolute path to the PKCS#12 (.p12 / .pfx) file.
     * @return List of available certificates or an error.
     */
    suspend operator fun invoke(filePath: String): OperationResult<List<AvailableCertificateInfo>> =
        signingRepository.loadCertificatesFromFile(filePath)
}
