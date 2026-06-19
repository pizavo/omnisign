package cz.pizavo.omnisign.domain.model.error

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey

/**
 * Signing-specific errors.
 *
 * The case (subtype) is the stable category used for handling — the server maps each to an
 * HTTP status in `StatusPages.kt`. The specific user-facing message is carried as localizable
 * [LocalizableError.text], and the English [OperationError.message] is derived from it. Use the
 * [Companion] factories for the keyed (translatable) messages; reach for [LocalizableText.Literal]
 * only for propagated or runtime-computed text.
 */
sealed interface SigningError : OperationError, LocalizableError {

	/** English rendering of [text]; the fallback for non-localizing consumers (CLI, server, logs). */
	override val message: String get() = text.english()

	/**
	 * The token/certificate could not be accessed.
	 */
	data class TokenAccessError(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : SigningError

	/**
	 * Invalid signing parameters provided.
	 */
	data class InvalidParameters(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : SigningError

	/**
	 * The signing operation failed.
	 */
	data class SigningFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : SigningError

	/**
	 * The timestamp server could not be reached or returned an error.
	 */
	data class TimestampError(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : SigningError

	/**
	 * The selected hash algorithm has passed its ETSI TS 119 312 expiration date and the
	 * configured constraint level is [cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel.FAIL].
	 * Signing is blocked to prevent creation of immediately-invalid signatures.
	 */
	data class ExpiredAlgorithm(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : SigningError

	companion object {

		/** Token discovery failed unexpectedly. */
		fun discoverTokensFailed(details: String? = null, cause: Throwable? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_DISCOVER_TOKENS_FAILED), details, cause)

		/** The user cancelled the PIN prompt for the token named [tokenName]. */
		fun pinEntryCancelled(tokenName: String): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_PIN_ENTRY_CANCELLED, tokenName))

		/** The DSS signing token could not be created from the selected certificate. */
		fun createSigningTokenFailed(details: String? = null, cause: Throwable? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_CREATE_TOKEN_FAILED), details, cause)

		/** The keystore file at [path] does not exist. */
		fun fileNotFound(path: String): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_FILE_NOT_FOUND, path))

		/** Certificates could not be loaded from the token named [tokenName]. */
		fun loadCertificatesFromTokenFailed(
			tokenName: String,
			details: String? = null,
			cause: Throwable? = null,
		): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_LOAD_CERTS_FROM_TOKEN_FAILED, tokenName), details, cause)

		/** Listing the available certificates across discovered tokens failed unexpectedly. */
		fun listCertificatesFailed(details: String? = null, cause: Throwable? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_LIST_CERTS_FAILED), details, cause)

		/** The token identified by [tokenId] is not among the discovered tokens. */
		fun tokenNotFound(tokenId: String): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_TOKEN_NOT_FOUND, tokenId))

		/** Unlocking a previously locked token failed unexpectedly. */
		fun unlockTokenFailed(details: String? = null, cause: Throwable? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_UNLOCK_TOKEN_FAILED), details, cause)

		/** The user cancelled the password prompt for a PKCS#12 file. */
		fun passwordEntryCancelled(): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_PASSWORD_ENTRY_CANCELLED))

		/** Certificates could not be loaded from a PKCS#12 file. */
		fun loadCertificatesFromFileFailed(details: String? = null, cause: Throwable? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_LOAD_CERTS_FROM_FILE_FAILED), details, cause)

		/** No certificate was available to sign with (no alias was requested). */
		fun noCertificateFound(): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_NO_CERT_FOUND))

		/** No certificate matching the requested [alias] was found. */
		fun noCertificateFoundForAlias(alias: String): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_NO_CERT_FOUND_FOR_ALIAS, alias))

		/** Remote (server-delegated) signing failed. */
		fun remoteSigningFailed(details: String? = null, cause: Throwable? = null): SigningFailed =
			SigningFailed(LocalizableText.of(MessageKey.SIGNING_REMOTE_SIGNING_FAILED), details, cause)

		/** The signing operation failed for an unclassified reason. */
		fun signingFailed(details: String? = null, cause: Throwable? = null): SigningFailed =
			SigningFailed(LocalizableText.of(MessageKey.SIGNING_SIGNING_FAILED), details, cause)

		/** Listing certificates from the server failed. */
		fun listCertificatesFromServerFailed(details: String? = null, cause: Throwable? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_LIST_CERTS_FROM_SERVER_FAILED), details, cause)

		/** Token unlock is not available on the web target. */
		fun tokenUnlockNotSupportedOnWeb(details: String? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_UNLOCK_NOT_SUPPORTED_WEB), details)

		/** Loading a PKCS#12 file is not available on the web target. */
		fun loadFileNotSupportedOnWeb(details: String? = null): TokenAccessError =
			TokenAccessError(LocalizableText.of(MessageKey.SIGNING_LOAD_FILE_NOT_SUPPORTED_WEB), details)

		/** The chosen hash [hash] is incompatible with the chosen encryption [encryption]. */
		fun hashEncryptionIncompatible(hash: String, encryption: String, compatibleHashes: String): InvalidParameters =
			InvalidParameters(
				LocalizableText.of(MessageKey.SIGNING_HASH_ENCRYPTION_INCOMPATIBLE, hash, encryption, compatibleHashes)
			)

		/** The chosen hash [hash] is not supported by the Windows Certificate Store. */
		fun hashNotSupportedByWindowsStore(hash: String, details: String? = null): InvalidParameters =
			InvalidParameters(LocalizableText.of(MessageKey.SIGNING_HASH_NOT_SUPPORTED_WINDOWS, hash), details)
	}
}
