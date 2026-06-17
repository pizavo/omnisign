package cz.pizavo.omnisign.domain.model.error

/**
 * Archiving/LTA-specific errors.
 */
sealed interface ArchivingError : OperationError {
    /**
     * Failed to add revocation information.
     */
    data class RevocationInfoError(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ArchivingError
    
    /**
     * Failed to extend signature to LTA format.
     */
    data class ExtensionFailed(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ArchivingError

    /**
     * The timestamp server could not be reached or returned an error during extension.
     */
    data class TimestampFailed(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
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
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ArchivingError

    /**
     * The input PDF is encrypted or password-protected, so the in-place modification an extension
     * requires is not possible. Supplying the document's password is out of scope for unattended
     * renewal and archiving.
     */
    data class EncryptedDocument(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ArchivingError

    /**
     * The input is not a valid PDF, or is corrupted, and could not be parsed for extension.
     */
    data class MalformedDocument(
        override val message: String,
        override val details: String? = null,
        override val cause: Throwable? = null
    ) : ArchivingError
}
