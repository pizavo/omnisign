package cz.pizavo.omnisign.domain.model.error

/**
 * Configuration-related errors.
 */
sealed interface ConfigurationError : OperationError {
    /**
     * Configuration could not be loaded.
     */
    data class LoadFailed(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ConfigurationError
    
    /**
     * Configuration could not be saved.
     */
    data class SaveFailed(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ConfigurationError
    
    /**
     * Configuration validation failed.
     */
    data class InvalidConfiguration(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ConfigurationError
}

