package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.SigningRepository

/**
 * Use case for signing documents.
 */
class SignDocumentUseCase(
    private val signingRepository: SigningRepository
) {
    /**
     * Execute document signing.
     *
     * @param parameters Signing parameters
     * @return Signing result or error
     */
    suspend operator fun invoke(parameters: SigningParameters): OperationResult<SigningResult> {
        return signingRepository.signDocument(parameters)
    }
}


