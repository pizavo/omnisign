package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.value.DateFormat
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The selectable value of the Language & Region preset dropdown.
 *
 * Models the three observable states of the language/date-format pair: the [System] default,
 * a concrete bundled [Preset], or a [Custom] combination the user assembled by overriding the
 * language or format independently. [Custom] is never directly selectable — it is only surfaced
 * (as a disabled trigger value) to label a combination that matches no preset.
 */
sealed interface RegionChoice {

	/** No region forced: the UI follows the system/browser locale and the default date format. */
	data object System : RegionChoice

	/** A bundled region preset pairing a language with its conventional date format. */
	data class Preset(val value: RegionPreset) : RegionChoice

	/** A user-assembled language/format combination that matches no preset. Not selectable. */
	data object Custom : RegionChoice

	/** Human-readable label for this choice, resolved in the current locale. */
	@Composable
	fun label(): String = when (this) {
		System -> stringResource(Res.string.settings_region_system_default)
		is Preset -> value.label()
		Custom -> stringResource(Res.string.settings_region_custom)
	}

	companion object {

		/**
		 * Derives the current [RegionChoice] from the active language/date-format pair.
		 *
		 * @param languageTag The active language tag (`null` = system default).
		 * @param dateFormat The active date format.
		 * @return [System] when nothing is forced, a matching [Preset] when the pair maps to a
		 *   preset, or [Custom] otherwise.
		 */
		fun of(languageTag: String?, dateFormat: DateFormat): RegionChoice = when {
			languageTag == null && dateFormat == DateFormat.SYSTEM -> System
			else -> RegionPreset.match(languageTag, dateFormat)?.let { Preset(it) } ?: Custom
		}
	}
}
