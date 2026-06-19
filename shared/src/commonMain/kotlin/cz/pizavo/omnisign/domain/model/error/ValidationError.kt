package cz.pizavo.omnisign.domain.model.error

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey

/**
 * Validation-specific errors.
 *
 * The case (subtype) is the stable category used for handling — the server maps each to an
 * HTTP status in `StatusPages.kt`. The specific user-facing message is carried as localizable
 * [LocalizableError.text], and the English [OperationError.message] is derived from it. Use the
 * [Companion] factories for the keyed (translatable) messages; reach for [LocalizableText.Literal]
 * only for propagated or runtime-computed text.
 */
sealed interface ValidationError : OperationError, LocalizableError {

	/** English rendering of [text]; the fallback for non-localizing consumers (CLI, server, logs). */
	override val message: String get() = text.english()

	/**
	 * The document could not be read or parsed.
	 */
	data class InvalidDocument(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ValidationError

	/**
	 * The validation policy could not be loaded or is invalid.
	 */
	data class InvalidPolicy(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ValidationError

	/**
	 * An error occurred during the validation process.
	 */
	data class ValidationFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ValidationError

	companion object {

		/** Local (DSS-backed) document validation failed for an unclassified reason. */
		fun validationFailed(details: String? = null, cause: Throwable? = null): ValidationFailed =
			ValidationFailed(LocalizableText.of(MessageKey.VALIDATION_VALIDATION_FAILED), details, cause)

		/** Remote (server-delegated) document validation failed. */
		fun remoteValidationFailed(details: String? = null, cause: Throwable? = null): ValidationFailed =
			ValidationFailed(LocalizableText.of(MessageKey.VALIDATION_REMOTE_VALIDATION_FAILED), details, cause)
	}
}
