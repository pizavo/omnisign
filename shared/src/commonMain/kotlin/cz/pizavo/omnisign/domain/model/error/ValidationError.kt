package cz.pizavo.omnisign.domain.model.error

/**
 * Validation-specific errors.
 */
sealed interface ValidationError : OperationError {
    /**
     * The document could not be read or parsed.
     */
    data class InvalidDocument(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ValidationError
    
    /**
     * The validation policy could not be loaded or is invalid.
     */
    data class InvalidPolicy(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ValidationError
    
    /**
     * An error occurred during the validation process.
     */
    data class ValidationFailed(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ValidationError
}

