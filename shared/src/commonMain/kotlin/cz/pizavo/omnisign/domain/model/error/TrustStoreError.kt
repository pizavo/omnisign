package cz.pizavo.omnisign.domain.model.error

/**
 * Errors raised by the directly-trusted certificate store.
 */
sealed interface TrustStoreError : OperationError {
	/**
	 * The supplied bytes could not be parsed as an X.509 certificate.
	 */
	data class ParseFailed(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : TrustStoreError

	/**
	 * The referenced certificate was not found in the target scope.
	 */
	data class NotFound(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : TrustStoreError

	/**
	 * Reading or writing the trust directory or its index failed.
	 */
	data class StorageFailed(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : TrustStoreError
}
