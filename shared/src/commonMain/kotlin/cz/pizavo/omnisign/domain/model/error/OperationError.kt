package cz.pizavo.omnisign.domain.model.error

/**
 * Base interface for all operation errors in the application.
 * All error types should implement this interface to provide consistent error handling.
 */
sealed interface OperationError {
    val message: String
    val details: String?
    val cause: Throwable?
}

