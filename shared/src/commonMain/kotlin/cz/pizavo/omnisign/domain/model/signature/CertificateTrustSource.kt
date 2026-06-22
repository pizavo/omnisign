package cz.pizavo.omnisign.domain.model.signature

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import kotlinx.serialization.Serializable

/**
 * Where a chain certificate's trust comes from, under the environment the validation ran in — a
 * snapshot used by the certificate-details dialog to show why each certificate is trusted. A single
 * certificate may be trusted via several sources at once (e.g. a trusted list and the global store).
 */
@Serializable
sealed interface CertificateTrustSource {

    /**
     * Trusted as an anchor published on a trusted list (e.g. the EU LOTL).
     *
     * @property name The list's display name (e.g. `"EU LOTL"`), or `null` for a generic
     *   trusted-list anchor whose specific list is not identified — rendered with a localized
     *   "Trusted list" label.
     */
    @Serializable
    data class TrustedList(val name: String?) : CertificateTrustSource

    /** Trusted because it is in the app's global trust store. */
    @Serializable
    data object GlobalStore : CertificateTrustSource

    /** Trusted because it is in the named profile's trust store. */
    @Serializable
    data class ProfileStore(val profileName: String) : CertificateTrustSource
}

/**
 * Localizable label for this trust source — the trusted list's name (a [LocalizableText.Literal], or a
 * keyed generic "Trusted list" when the list is unnamed), or a keyed global-trust-store /
 * profile-store label. Shared by every surface that names a certificate's
 * trust origin so the wording never drifts: the certificate-details dialog resolves it to the active
 * locale, while the plain-text report and the CLI render its [LocalizableText.english].
 */
fun CertificateTrustSource.displayLabel(): LocalizableText = when (this) {
    is CertificateTrustSource.TrustedList -> name?.let { LocalizableText.Literal(it) } ?: LocalizableText.of(MessageKey.TRUST_SOURCE_TRUSTED_LIST)
    CertificateTrustSource.GlobalStore -> LocalizableText.of(MessageKey.TRUST_SOURCE_GLOBAL_STORE)
    is CertificateTrustSource.ProfileStore -> LocalizableText.of(MessageKey.TRUST_SOURCE_PROFILE, profileName)
}
