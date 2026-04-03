package cz.pizavo.omnisign.ui.model

/**
 * Navigation mode for the profiles side panel.
 *
 * Determines whether the panel displays the profile list or an edit form.
 */
sealed interface ProfilePanelMode {

    /**
     * The default mode showing the list of all profiles.
     */
    data object Listing : ProfilePanelMode

    /**
     * Editing an existing profile identified by [profileName].
     *
     * @property profileName The name of the profile being edited.
     */
    data class Editing(val profileName: String) : ProfilePanelMode
}

