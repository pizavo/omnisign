package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

/**
 * UI state for the profiles panel.
 *
 * @property profiles All available configuration profiles.
 * @property activeProfile Name of the currently active profile, or `null` if none is selected.
 * @property loading Whether a profile list refresh is in progress.
 * @property error Error from the last failed operation, or `null`; the UI resolves it via `resolve()`.
 * @property creatingNew Whether the inline new-profile row is currently displayed.
 * @property mode Current navigation mode of the panel (list or edit).
 * @property editState Mutable form state when [mode] is [ProfilePanelMode.Editing], `null` otherwise.
 * @property globalDisabledHashAlgorithms Hash algorithms disabled at the global level,
 *   used to grey-out unavailable options in profile algorithm selectors.
 * @property globalDisabledEncryptionAlgorithms Encryption algorithms disabled at the global level,
 *   used to grey-out unavailable options in profile algorithm selectors.
 * @property globalSignatureLevel The global default signature level, B-B until the configuration
 *   loads. While it is B-LTA, an archival timestamp toggle left on Inherit is active, and the
 *   profile form shows the signature timestamp toggle forced on (see
 *   [ProfileEditState.archivalTimestampActive]). A save resolves the toggles against the global
 *   level as stored at that time, not against this copy.
 */
data class ProfileListState(
    val profiles: List<ProfileConfig> = emptyList(),
    val activeProfile: String? = null,
    val loading: Boolean = false,
    val error: ProfileError? = null,
    val creatingNew: Boolean = false,
    val mode: ProfilePanelMode = ProfilePanelMode.Listing,
    val editState: ProfileEditState? = null,
    val globalDisabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
    val globalDisabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
    val globalSignatureLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_B,
)

