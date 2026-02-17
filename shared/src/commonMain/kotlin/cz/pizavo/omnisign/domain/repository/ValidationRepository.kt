package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.ValidationReport

/**
 * Repository for document validation operations.
 */
interface ValidationRepository {
    /**
     * Validate a signed document.
     *
     * @param parameters Validation parameters
     * @return Validation report or error
     */
    suspend fun validateDocument(parameters: ValidationParameters): OperationResult<ValidationReport>
}

