package cz.pizavo.omnisign.domain.model.error

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey

/**
 * Archiving/LTA-specific errors.
 *
 * The case (subtype) is the stable category used for handling — the server maps each to an
 * HTTP status, and the UI distinguishes [RevocationInfoError] for its revocation-warning
 * flow. The specific user-facing message is carried as localizable [LocalizableError.text],
 * and the English [OperationError.message] is derived from it. Use the [Companion] factories
 * for the keyed (translatable) messages.
 */
sealed interface ArchivingError : OperationError, LocalizableError {

	/** English rendering of [text]; the fallback for non-localizing consumers (CLI, server, logs). */
	override val message: String get() = text.english()

	/**
	 * Failed to add revocation information.
	 */
	data class RevocationInfoError(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ArchivingError

	/**
	 * Failed to extend the signature to the requested format — the catch-all archiving failure.
	 */
	data class ExtensionFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ArchivingError

	/**
	 * The timestamp server could not be reached or returned an error during extension.
	 */
	data class TimestampFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ArchivingError

	/**
	 * A document's renewal status could not be determined because a renewal-relevant timestamp's
	 * signing (TSA) certificate — and therefore its expiry — could not be resolved.
	 *
	 * A conformant PAdES LT/LTA archive embeds the validation material needed to resolve every
	 * timestamp's signing certificate, so an unresolvable certificate signals a non-conformant or
	 * lower-level document rather than a safe one. The check reports it as an error instead of
	 * silently treating the document as not needing renewal.
	 */
	data class RenewalStatusUndeterminable(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ArchivingError

	/**
	 * The input PDF is encrypted or password-protected, so the in-place modification an extension
	 * requires is not possible. Supplying the document's password is out of scope for unattended
	 * renewal and archiving.
	 */
	data class EncryptedDocument(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ArchivingError

	/**
	 * The input is not a valid PDF, or is corrupted, and could not be parsed for extension.
	 */
	data class MalformedDocument(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ArchivingError

	companion object {

		/** Extension target level is not higher than the document's current level. */
		fun targetLevelNotHigher(): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_TARGET_LEVEL_NOT_HIGHER))

		/** Extension to [targetLevel] needs a timestamp server, but none is configured. */
		fun timestampServerRequired(targetLevel: String): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_TSA_REQUIRED, targetLevel))

		/** The input PDF is encrypted or password-protected. */
		fun pdfEncrypted(details: String? = null, cause: Throwable? = null): EncryptedDocument =
			EncryptedDocument(LocalizableText.of(MessageKey.ARCHIVING_PDF_ENCRYPTED), details, cause)

		/** The input is not a valid PDF or could not be parsed. */
		fun malformedPdf(details: String? = null, cause: Throwable? = null): MalformedDocument =
			MalformedDocument(LocalizableText.of(MessageKey.ARCHIVING_MALFORMED_PDF), details, cause)

		/** Revocation information for the signature could not be obtained. */
		fun revocationInfoFailed(details: String? = null, cause: Throwable? = null): RevocationInfoError =
			RevocationInfoError(LocalizableText.of(MessageKey.ARCHIVING_REVOCATION_INFO_FAILED), details, cause)

		/** A document extension failed for an unclassified reason. */
		fun extensionFailed(details: String? = null, cause: Throwable? = null): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_EXTENSION_FAILED), details, cause)

		/** The referenced file does not exist. */
		fun fileNotFound(path: String): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_FILE_NOT_FOUND, path))

		/** Renewal status could not be determined (unresolvable timestamp signing certificate). */
		fun renewalStatusUndeterminable(details: String? = null): RenewalStatusUndeterminable =
			RenewalStatusUndeterminable(LocalizableText.of(MessageKey.ARCHIVING_RENEWAL_STATUS_UNDETERMINABLE), details)

		/** The archival-renewal check failed for an unclassified reason. */
		fun renewalCheckFailed(details: String? = null, cause: Throwable? = null): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_RENEWAL_CHECK_FAILED), details, cause)

		/** The document's timestamp state could not be inspected. */
		fun timestampInspectFailed(details: String? = null, cause: Throwable? = null): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_TIMESTAMP_INSPECT_FAILED), details, cause)

		/** A server-delegated document extension failed (web target). */
		fun remoteExtensionFailed(details: String? = null, cause: Throwable? = null): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_REMOTE_EXTENSION_FAILED), details, cause)

		/** Archival-renewal checks are unavailable on the web target (no filesystem access). */
		fun webRenewalUnsupported(details: String? = null): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_WEB_RENEWAL_UNSUPPORTED), details)

		/** A server-delegated document inspection failed (web target). */
		fun remoteInspectFailed(details: String? = null, cause: Throwable? = null): ExtensionFailed =
			ExtensionFailed(LocalizableText.of(MessageKey.ARCHIVING_REMOTE_INSPECT_FAILED), details, cause)
	}
}
