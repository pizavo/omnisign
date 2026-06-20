package cz.pizavo.omnisign.domain.model.value

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.serialization.Serializable

/**
 * Selectable date-display format for the UI.
 *
 * [SYSTEM] keeps the app's default long, readable style; the remaining entries are the common
 * regional numeric patterns (language-neutral). Only the date portion differs — [InstantFormatter]
 * appends the time and UTC offset uniformly regardless of the chosen format.
 *
 * Serialized by entry name (the [pattern] is implementation detail and never persisted), so the
 * enum is safe to store in [FormatPreferences] and reload across the CLI, desktop, and web.
 *
 * @property displayPattern Short, language-neutral hint for the format — a mask like `dd/mm/yyyy` for
 *   the numeric entries, or a brief description for [SYSTEM]. Surfaced by the CLI's `config date-format`
 *   listing and the desktop format dropdown.
 */
@Serializable
enum class DateFormat(
	internal val pattern: DateTimeFormat<LocalDate>,
	val displayPattern: String,
) {

	/** Long, readable style, e.g. `Sat, 14 March 2026` — the app default. */
	SYSTEM(LocalDate.Format {
		dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
		chars(", ")
		day(Padding.NONE)
		char(' ')
		monthName(MonthNames.ENGLISH_FULL)
		char(' ')
		year(Padding.NONE)
	}, "long readable"),

	/** Day/month/year with slashes, e.g. `14/03/2026`. */
	DMY_SLASH(LocalDate.Format {
		day()
		char('/')
		monthNumber()
		char('/')
		year()
	}, "dd/mm/yyyy"),

	/** Day.month.year with dots, e.g. `14.03.2026`. */
	DMY_DOT(LocalDate.Format {
		day()
		char('.')
		monthNumber()
		char('.')
		year()
	}, "dd.mm.yyyy"),

	/** Month/day/year with slashes, e.g. `03/14/2026`. */
	MDY_SLASH(LocalDate.Format {
		monthNumber()
		char('/')
		day()
		char('/')
		year()
	}, "mm/dd/yyyy"),

	/** ISO 8601 year-month-day with dashes, e.g. `2026-03-14`. */
	ISO_8601(LocalDate.Format {
		year()
		char('-')
		monthNumber()
		char('-')
		day()
	}, "yyyy-mm-dd"),
}
