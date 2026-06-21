package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.value.DateFormat

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
	CZECHIA("cs", DateFormat.DMY_DOT),

	/** Slovak UI with day.month.year dots (`dd.mm.yyyy`). */
	SLOVAKIA("sk", DateFormat.DMY_DOT);

	/**
	 * The native-name (endonym) region label shown in the preset dropdown.
	 *
	 * Intentionally not translated — each region is presented in its own language so it reads
	 * identically in any UI locale, mirroring the language dropdown's endonyms. The `System` and
	 * `Custom` meta-options stay localized via [RegionChoice].
	 */
	fun label(): String = when (this) {
		UNITED_KINGDOM -> "United Kingdom"
		UNITED_STATES -> "United States"
		CZECHIA -> "Česko"
		SLOVAKIA -> "Slovensko"
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
