package cz.pizavo.omnisign.domain.model.result

import arrow.core.Either
import cz.pizavo.omnisign.domain.model.error.OperationError

/**
 * Type alias for operation results using Arrow Either.
 *
 * Arrow 2.x provides full Kotlin Multiplatform support including Wasm.
 * Left side contains errors, Right side contains success values.
 *
 * Example usage:
 * ```kotlin
 * fun validateDocument(file: String): OperationResult<ValidationReport> {
 *     return Either.catch {
 *         // perform validation
 *     }.mapLeft { error ->
 *         ValidationError.ValidationFailed("Failed", error.message, error)
 *     }
 * }
 * ```
 */
typealias OperationResult<T> = Either<OperationError, T>

