package cz.pizavo.omnisign.domain.model.error

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.TrustScope

/**
 * Errors raised by the directly-trusted certificate store.
 *
 * The case (subtype) is the stable category used for handling — the server maps each to an
 * HTTP status in `StatusPages.kt`. The specific user-facing message is carried as localizable
 * [LocalizableError.text], and the English [OperationError.message] is derived from it. Use the
 * [Companion] factories for the keyed (translatable) messages; reach for [LocalizableText.Literal]
 * only for propagated or runtime-computed text.
 */
sealed interface TrustStoreError : OperationError, LocalizableError {

	/** English rendering of [text]; the fallback for non-localizing consumers (CLI, server, logs). */
	override val message: String get() = text.english()

	/**
	 * The supplied bytes could not be parsed as an X.509 certificate.
	 */
	data class ParseFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : TrustStoreError

	/**
	 * The referenced certificate was not found in the target scope.
	 */
	data class NotFound(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : TrustStoreError

	/**
	 * Reading or writing the trust directory or its index failed.
	 */
	data class StorageFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : TrustStoreError

	companion object {

		/** The supplied bytes could not be parsed as an X.509 certificate. */
		fun parseFailed(details: String? = null, cause: Throwable? = null): ParseFailed =
			ParseFailed(LocalizableText.of(MessageKey.TRUSTSTORE_PARSE_FAILED), details, cause)

		/** Writing the certificate into the trust store failed. */
		fun storageFailed(details: String? = null, cause: Throwable? = null): StorageFailed =
			StorageFailed(LocalizableText.of(MessageKey.TRUSTSTORE_STORE_FAILED), details, cause)

		/** A trust store operation failed for an unclassified reason. */
		fun operationFailed(details: String? = null, cause: Throwable? = null): StorageFailed =
			StorageFailed(LocalizableText.of(MessageKey.TRUSTSTORE_OPERATION_FAILED), details, cause)

		/**
		 * No stored certificate [fingerprint] exists to reference from [scope]; the scope phrasing
		 * (global vs. a named profile) is baked into the chosen message variant.
		 */
		fun noStoredCertificate(fingerprint: String, scope: TrustScope): NotFound = when (scope) {
			is TrustScope.Global -> NotFound(LocalizableText.of(MessageKey.TRUSTSTORE_NO_STORED_CERT_GLOBAL, fingerprint))
			is TrustScope.Profile -> NotFound(LocalizableText.of(MessageKey.TRUSTSTORE_NO_STORED_CERT_PROFILE, fingerprint, scope.name))
		}

		/**
		 * No trusted certificate [fingerprint] is present in [scope]; the scope phrasing (global vs.
		 * a named profile) is baked into the chosen message variant.
		 */
		fun notFoundInScope(fingerprint: String, scope: TrustScope): NotFound = when (scope) {
			is TrustScope.Global -> NotFound(LocalizableText.of(MessageKey.TRUSTSTORE_NOT_FOUND_GLOBAL, fingerprint))
			is TrustScope.Profile -> NotFound(LocalizableText.of(MessageKey.TRUSTSTORE_NOT_FOUND_PROFILE, fingerprint, scope.name))
		}
	}
}
