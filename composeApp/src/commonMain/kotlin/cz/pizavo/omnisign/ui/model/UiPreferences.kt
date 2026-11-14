package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.value.FormatPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Aggregated user-interface preferences for the desktop and web client.
 *
 * The chrome fields — [isDark], [useNativeTitleBar], [languageTag] — are owned solely by this
 * client: the desktop persists them as `preferences/ui.json`, the web in the browser's
 * `localStorage`. [format] is deliberately **not** stored with them: it is [Transient] and composed
 * in at load time from its own store, on the desktop the `preferences/format.json` file shared with
 * the CLI, so the cross-surface date format keeps a single source of truth, and on the web a
 * separate `localStorage` entry. Writes are field-level (read-modify-write) so the independent
 * theme / language / title-bar updates never clobber one another.
 *
 * @property format Cross-surface formatting preferences, composed in from their own store at load
 *   time; never written with the chrome fields.
 * @property isDark Persisted dark/light theme choice, or `null` to follow the system default.
 * @property useNativeTitleBar Linux-only: use the native OS title bar instead of the merged toolbar,
 *   or `null` for the platform default.
 * @property languageTag Forced BCP 47 UI language tag, or `null` to follow the system/browser locale.
 */
@Serializable
data class UiPreferences(
	@Transient val format: FormatPreferences = FormatPreferences(),
	val isDark: Boolean? = null,
	val useNativeTitleBar: Boolean? = null,
	val languageTag: String? = null,
)
