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
}
