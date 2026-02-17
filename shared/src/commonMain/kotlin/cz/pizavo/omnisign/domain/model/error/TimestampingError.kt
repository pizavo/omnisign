package cz.pizavo.omnisign.domain.model.error

/**
 * Timestamping-specific errors.
 */
sealed interface TimestampingError : OperationError {
    /**
     * The document could not be timestamped.
     */
    data class TimestampFailed(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : TimestampingError
    
    /**
     * The timestamp server is unreachable or invalid.
     */
    data class ServerUnreachable(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : TimestampingError
}

