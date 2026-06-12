package cz.pizavo.omnisign.ui.layout

import androidx.compose.runtime.compositionLocalOf
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType

/**
 * Capability to add a certificate directly to the trust store, surfaced to the certificate-details
 * dialog (deep inside the validation report) via [LocalTrustedCertificateAdder] so the intermediate
 * composables need not thread it through — mirroring how `LocalToastService` is provided at the
 * layout root and consumed in dialogs.
 *
 * Provided where the trust store and the active profile are known (the island layout); absent
 * (`null`) on targets without a trust store (web), which hides the "add to trusted" action.
 *
 * @property activeProfileName Name of the currently selected profile, or `null` when none is
 *   selected — in which case only the global scope is offered.
 * @property add Adds [der] to the trust store with the given trust [type]: to the active profile's
 *   scope when [toActiveProfile] is `true` (and a profile is selected), otherwise to the global
 *   scope. Returns `null` on success or a human-readable error message on failure. Commits directly
 *   — there is no staging.
 */
class TrustedCertificateAdder(
    val activeProfileName: String?,
    val add: suspend (der: ByteArray, toActiveProfile: Boolean, type: TrustedCertificateType) -> String?,
)

/**
 * Provides the [TrustedCertificateAdder] to the certificate-details dialog; `null` when the platform
 * has no trust store, which hides the add-to-trusted control.
 */
val LocalTrustedCertificateAdder = compositionLocalOf<TrustedCertificateAdder?> { null }
