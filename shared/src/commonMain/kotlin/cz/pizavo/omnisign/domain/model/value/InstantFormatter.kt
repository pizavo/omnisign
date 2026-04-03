package cz.pizavo.omnisign.domain.model.value

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Formats [kotlin.time.Instant] values into human-readable date/time strings.
 *
 * Uses the system default [TimeZone] for conversion so that times appear local to the user.
 * Separate functions are provided for date-only and full date-time representations.
 */
object InstantFormatter {

    /**
     * Format an [Instant] as a full date-time string in the system default timezone.
     *
     * Example output: `Sat, 14 March 2026, 10:00:00 (+01:00)`
     *
     * @param instant Point in time to format.
     * @param timeZone Optional timezone override; defaults to [TimeZone.currentSystemDefault].
     */
    fun formatDateTime(instant: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
	    val local = instant.toLocalDateTime(timeZone)
	    val offset = instant.offsetIn(timeZone)
	    val formatter = LocalDateTime.Format {
			dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
			chars(", ")
			day(Padding.NONE)
			char(' ')
			monthName(MonthNames.ENGLISH_FULL)
			char(' ')
			year(Padding.NONE)
			chars(", ")
		    hour()
		    char(':')
		    minute()
		    char(':')
		    second()
		    chars(" ($offset)")
		}
		
	    return formatter.format(local)
    }

    /**
     * Format an [Instant] as a date-only string in the system default timezone.
     *
     * Example output: `Sat, 14 March 2026`
     *
     * @param instant Point in time to format.
     * @param timeZone Optional timezone override; defaults to [TimeZone.currentSystemDefault].
     */
    fun formatDate(instant: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = instant.toLocalDateTime(timeZone)
        return DATE_FORMATTER.format(local.date)
    }

    private val DATE_FORMATTER = LocalDate.Format {
        dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
        chars(", ")
        day(Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        year(Padding.NONE)
    }
}

/**
 * Format this [Instant] as a full date-time string in the system default timezone.
 *
 * @see InstantFormatter.formatDateTime
 */
fun Instant.formatDateTime(timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
    InstantFormatter.formatDateTime(this, timeZone)

/**
 * Format this [Instant] as a date-only string in the system default timezone.
 *
 * @see InstantFormatter.formatDate
 */
fun Instant.formatDate(timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
    InstantFormatter.formatDate(this, timeZone)

