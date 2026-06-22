package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.value.FormatPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Aggregated user-interface preferences for the desktop and web client.
 *
 * The chrome fields — [isDark], [useNativeTitleBar], [languageTag] — are persisted as
 * `preferences/ui.json` and are owned solely by this client. [format] is deliberately **not** stored
 * here: it is [Transient] and composed in at load time from the shared `preferences/format.json`
 * store, so the cross-surface date format keeps a single source of truth shared with the CLI. Writes
 * are field-level (read-modify-write) so the independent theme / language / title-bar updates never
 * clobber one another in the shared `ui.json` file.
 *
 * @property format Cross-surface formatting preferences, composed in from the shared store at load
 *   time; never written into `ui.json`.
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
