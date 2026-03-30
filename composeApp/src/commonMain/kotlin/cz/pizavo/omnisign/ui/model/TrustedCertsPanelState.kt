package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig

/**
 * UI state for the trusted certificates overview panel.
 *
 * Presents two distinct sections so the user can see at a glance which certificates
 * are contributed by the active profile and which come from the global configuration.
 *
 * @property profileName Name of the active profile, or `null` when no profile is active.
 * @property profileCertificates Certificates scoped to the active profile.
 * @property globalCertificates Certificates registered in the global configuration.
 * @property loading Whether a config load is currently in progress.
 * @property error Human-readable error message from the last failed operation, or `null`.
 */
data class TrustedCertsPanelState(
    val profileName: String? = null,
    val profileCertificates: List<TrustedCertificateConfig> = emptyList(),
    val globalCertificates: List<TrustedCertificateConfig> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

