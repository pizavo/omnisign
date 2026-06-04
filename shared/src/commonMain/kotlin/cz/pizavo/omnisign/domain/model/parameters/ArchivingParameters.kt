package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

/**
 * Parameters for extending an already-signed PDF to a higher PAdES level.
 *
 * The document is carried as raw bytes rather than a filesystem path so the same parameters
 * flow through every entry point: the CLI and desktop read the bytes off disk, the server
 * reads them from the multipart upload, and the web target ships them to the server. The
 * on-disk destination, where one exists, is chosen by the caller at the platform boundary.
 *
 * @property inputBytes Raw bytes of the signed PDF to extend.
 * @property inputName Original document name, used for diagnostics and as the suggested output name.
 * @property targetLevel The PAdES level to extend to — must be higher than the current document
 *   level.  Use [SignatureLevel.PADES_BASELINE_T] to add a timestamp to a B-B document,
 *   [SignatureLevel.PADES_BASELINE_LT] to embed revocation data, or
 *   [SignatureLevel.PADES_BASELINE_LTA] for a full archival timestamp.
 * @property resolvedConfig Pre-resolved configuration; falls back to the active config when null.
 *   Honoured by the JVM ([cz.pizavo.omnisign.domain.repository.ArchivingRepository]) implementation.
 * @property profileName Named configuration profile to resolve server-side. Ignored by the JVM
 *   implementation (which uses [resolvedConfig]); forwarded by the web client so the server
 *   resolves the same profile the user selected.
 * @property disabledHashAlgorithms Hash algorithms to additionally disable for this request,
 *   union-merged server-side. Ignored by the JVM implementation.
 * @property disabledEncryptionAlgorithms Encryption algorithms to additionally disable for this
 *   request, union-merged server-side. Ignored by the JVM implementation.
 */
data class ArchivingParameters(
    val inputBytes: ByteArray,
    val inputName: String,
    val targetLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_LTA,
    val resolvedConfig: ResolvedConfig? = null,
    val profileName: String? = null,
    val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
    val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
) {
    /**
     * Structural equality that compares [inputBytes] by content rather than by reference.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArchivingParameters) return false
        return inputBytes.contentEquals(other.inputBytes) &&
                inputName == other.inputName &&
                targetLevel == other.targetLevel &&
                resolvedConfig == other.resolvedConfig &&
                profileName == other.profileName &&
                disabledHashAlgorithms == other.disabledHashAlgorithms &&
                disabledEncryptionAlgorithms == other.disabledEncryptionAlgorithms
    }

    /**
     * Hash code consistent with [equals], hashing [inputBytes] by content.
     */
    override fun hashCode(): Int {
        var result = inputBytes.contentHashCode()
        result = 31 * result + inputName.hashCode()
        result = 31 * result + targetLevel.hashCode()
        result = 31 * result + (resolvedConfig?.hashCode() ?: 0)
        result = 31 * result + (profileName?.hashCode() ?: 0)
        result = 31 * result + disabledHashAlgorithms.hashCode()
        result = 31 * result + disabledEncryptionAlgorithms.hashCode()
        return result
    }
}