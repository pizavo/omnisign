package cz.pizavo.omnisign.domain.model.error

/**
 * Signing-specific errors.
 */
sealed interface SigningError : OperationError {
	/**
	 * The token/certificate could not be accessed.
	 */
	data class TokenAccessError(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null
	) : SigningError
	
	/**
	 * Invalid signing parameters provided.
	 */
	data class InvalidParameters(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null
	) : SigningError
	
	/**
	 * The signing operation failed.
	 */
	data class SigningFailed(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null
	) : SigningError
	
	/**
	 * The timestamp server could not be reached or returned an error.
	 */
	data class TimestampError(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null
	) : SigningError
	
	/**
	 * The selected hash algorithm has passed its ETSI TS 119 312 expiration date and the
	 * configured constraint level is [cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel.FAIL].
	 * Signing is blocked to prevent creation of immediately-invalid signatures.
	 */
	data class ExpiredAlgorithm(
		override val message: String,
		override val details: String? = null,
		override val cause: Throwable? = null
	) : SigningError
}

