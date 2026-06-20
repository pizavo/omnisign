package cz.pizavo.omnisign

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.ui.layout.IslandLayout
import cz.pizavo.omnisign.ui.platform.LocalAppDateFormat
import cz.pizavo.omnisign.ui.platform.LocalAppLocale
import cz.pizavo.omnisign.ui.platform.LocalTitleBarDarkControls
import cz.pizavo.omnisign.ui.platform.loadUiPreferences
import cz.pizavo.omnisign.ui.platform.saveFormatPreference
import cz.pizavo.omnisign.ui.platform.saveLanguagePreference
import cz.pizavo.omnisign.ui.platform.saveThemePreference

/**
 * Root composable for the OmniSign application.
 *
 * Wraps the entire UI in [LumoTheme] and renders the IntelliJ "Island"-inspired
 * desktop shell via [IslandLayout]. The dark/light theme toggle state is owned
 * here and threaded down to the layout and theme provider.
 *
 * On the first launch, the theme follows the OS preference. Once the user explicitly
 * toggles it, the choice is persisted via [saveThemePreference] and restored on
 * subsequent launches via [loadUiPreferences].
 *
 * The UI language and date format are likewise owned here. They are provided through
 * [LocalAppLocale] and [LocalAppDateFormat] so the entire composition re-resolves its
 * strings and dates when the user changes them in settings — without losing UI or ViewModel
 * state. All three are loaded together via [loadUiPreferences] and persisted individually via
 * [saveThemePreference] / [saveLanguagePreference] / [saveFormatPreference]; a `null` language
 * tag follows the system/browser locale.
 */
@Composable
@Preview
fun App() {
    val systemDark = isSystemInDarkTheme()
    val initialPreferences = remember { loadUiPreferences() }
    var isDarkTheme by remember { mutableStateOf(initialPreferences.isDark ?: systemDark) }
    var languageTag by remember { mutableStateOf(initialPreferences.languageTag) }
    var dateFormat by remember { mutableStateOf(initialPreferences.format.dateFormat) }

    val updateDarkControls = LocalTitleBarDarkControls.current
    SideEffect { updateDarkControls?.invoke(isDarkTheme) }

    CompositionLocalProvider(
        LocalAppLocale provides languageTag,
        LocalAppDateFormat provides dateFormat,
    ) {
        LumoTheme(isDarkTheme = isDarkTheme) {
            IslandLayout(
                isDarkTheme = isDarkTheme,
                onToggleTheme = {
                    isDarkTheme = !isDarkTheme
                    saveThemePreference(isDarkTheme)
                },
                languageTag = languageTag,
                dateFormat = dateFormat,
                onLanguageChange = { tag ->
                    saveLanguagePreference(tag)
                    languageTag = tag
                },
                onFormatChange = { fmt ->
                    saveFormatPreference(fmt)
                    dateFormat = fmt
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(LumoTheme.colors.background)
                    .safeContentPadding(),
            )
        }
    }
}