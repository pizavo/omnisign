package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.ProfileConfig

/**
 * UI state for the profiles panel.
 *
 * @property profiles All available configuration profiles.
 * @property activeProfile Name of the currently active profile, or `null` if none is selected.
 * @property loading Whether a profile list refresh is in progress.
 * @property error Human-readable error message from the last failed operation, or `null`.
 * @property creatingNew Whether the inline new-profile row is currently displayed.
 * @property mode Current navigation mode of the panel (list or edit).
 * @property editState Mutable form state when [mode] is [ProfilePanelMode.Editing], `null` otherwise.
 */
data class ProfileListState(
    val profiles: List<ProfileConfig> = emptyList(),
    val activeProfile: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val creatingNew: Boolean = false,
    val mode: ProfilePanelMode = ProfilePanelMode.Listing,
    val editState: ProfileEditState? = null,
)

