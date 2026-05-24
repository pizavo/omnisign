package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate

/**
 * UI state for the trusted certificates overview panel.
 *
 * Presents two distinct sections so the user can see at a glance which certificates
 * are referenced by the active profile scope and which come from the global scope.
 *
 * @property profileName Name of the active profile, or `null` when no profile is active.
 * @property profileCertificates Certificates referenced by the active profile scope.
 * @property globalCertificates Certificates referenced by the global scope.
 * @property available Whether an app-managed trust store backend is wired in. `false`
 *   on targets without a [cz.pizavo.omnisign.domain.repository.TrustStore] binding (web),
 *   where the panel renders disabled and read-only.
 * @property loading Whether a store read is currently in progress.
 * @property error Human-readable error message from the last failed operation, or `null`.
 * @property addError Human-readable error from the last failed certificate add attempt, or `null`.
 */
data class TrustedCertsPanelState(
    val profileName: String? = null,
    val profileCertificates: List<TrustedCertificate> = emptyList(),
    val globalCertificates: List<TrustedCertificate> = emptyList(),
    val available: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val addError: String? = null,
)
