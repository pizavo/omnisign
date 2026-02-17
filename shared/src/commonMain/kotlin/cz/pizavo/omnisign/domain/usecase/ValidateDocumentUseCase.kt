package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.repository.ValidationRepository

/**
 * Use case for validating signed documents.
 */
class ValidateDocumentUseCase(
    private val validationRepository: ValidationRepository
) {
    /**
     * Execute document validation.
     *
     * @param parameters Validation parameters
     * @return Validation report or error
     */
    suspend operator fun invoke(parameters: ValidationParameters): OperationResult<ValidationReport> {
        return validationRepository.validateDocument(parameters)
    }
}

