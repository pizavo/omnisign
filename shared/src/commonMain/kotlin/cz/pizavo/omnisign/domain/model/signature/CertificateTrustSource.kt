package cz.pizavo.omnisign.domain.model.signature

import kotlinx.serialization.Serializable

/**
 * Where a chain certificate's trust comes from, under the environment the validation ran in — a
 * snapshot used by the certificate-details dialog to show why each certificate is trusted. A single
 * certificate may be trusted via several sources at once (e.g. a trusted list and the global store).
 */
@Serializable
sealed interface CertificateTrustSource {

    /** Trusted as an anchor published on a trusted list (e.g. the EU LOTL). */
    @Serializable
    data class TrustedList(val name: String) : CertificateTrustSource

    /** Trusted because it is in the app's global trust store. */
    @Serializable
    data object GlobalStore : CertificateTrustSource

    /** Trusted because it is in the named profile's trust store. */
    @Serializable
    data class ProfileStore(val profileName: String) : CertificateTrustSource
}

/**
 * Human-readable label for this trust source — the trusted list's name, `"Global trust store"`, or
 * `"Profile: <name>"`. Shared by every surface that names a certificate's trust origin (the
 * certificate-details dialog, the plain-text report, the CLI) so the wording never drifts.
 */
fun CertificateTrustSource.displayLabel(): String = when (this) {
    is CertificateTrustSource.TrustedList -> name
    CertificateTrustSource.GlobalStore -> "Global trust store"
    is CertificateTrustSource.ProfileStore -> "Profile: $profileName"
}
