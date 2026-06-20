package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.value.DateFormat
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Bundled region presets that pair a UI language with a matching date format.
 *
 * Selecting a preset in the Language & Region settings applies both its [languageTag] and its
 * [dateFormat] in one step. The pairing is a convenience default only — the user may still override
 * either dimension independently afterwards, in which case no preset matches (see [Companion.match]).
 *
 * @property languageTag BCP 47 language tag applied for this region (e.g. `"en"`, `"cs"`).
 * @property dateFormat The numeric date format conventionally used in this region.
 */
enum class RegionPreset(val languageTag: String, val dateFormat: DateFormat) {

	/** English UI with day/month/year slashes (`dd/mm/yyyy`). */
	UNITED_KINGDOM("en", DateFormat.DMY_SLASH),

	/** English UI with month/day/year slashes (`mm/dd/yyyy`). */
	UNITED_STATES("en", DateFormat.MDY_SLASH),

	/** Czech UI with day.month.year dots (`dd.mm.yyyy`). */
	CZECHIA("cs", DateFormat.DMY_DOT);

	/** Human-readable region name displayed in the preset dropdown, resolved in the current locale. */
	@Composable
	fun label(): String = when (this) {
		UNITED_KINGDOM -> stringResource(Res.string.settings_region_united_kingdom)
		UNITED_STATES -> stringResource(Res.string.settings_region_united_states)
		CZECHIA -> stringResource(Res.string.settings_region_czechia)
	}

	companion object {

		/**
		 * Finds the preset whose language and date format both match the given pair.
		 *
		 * @param languageTag The active language tag (`null` = system default), as stored by the app.
		 * @param dateFormat The active date format.
		 * @return The matching [RegionPreset], or `null` when the pair corresponds to no preset
		 *   (e.g. the user picked a custom language/format combination, or the system default).
		 */
		fun match(languageTag: String?, dateFormat: DateFormat): RegionPreset? =
			entries.firstOrNull { it.languageTag == languageTag && it.dateFormat == dateFormat }
	}
}
