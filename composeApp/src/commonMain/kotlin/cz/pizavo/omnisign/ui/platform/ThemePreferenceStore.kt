package cz.pizavo.omnisign.ui.platform

/**
 * Loads the user's persisted dark/light theme preference.
 *
 * @return `true` for dark, `false` for light, or `null` when no preference
 *   has been saved yet (meaning the system default should be used).
 */
expect fun loadThemePreference(): Boolean?

/**
 * Persists the user's dark/light theme choice so it survives application restarts.
 *
 * @param isDark `true` to save a dark-theme preference, `false` for light.
 */
expect fun saveThemePreference(isDark: Boolean)

