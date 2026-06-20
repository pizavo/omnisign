package cz.pizavo.omnisign.domain.model.value

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Formats [kotlin.time.Instant] values into human-readable date/time strings.
 *
 * Uses the system default [TimeZone] for conversion so that times appear local to the user. The
 * date portion is rendered with the chosen [DateFormat] (defaulting to [DateFormat.SYSTEM], the
 * long readable style); the time and UTC offset are appended uniformly. Separate functions are
 * provided for date-only and full date-time representations.
 */
object InstantFormatter {

	private val TIME_FORMAT = LocalTime.Format {
		hour()
		char(':')
		minute()
		char(':')
		second()
	}

	/**
	 * Format an [Instant] as a full date-time string in the system default timezone.
	 *
	 * Example output (with [DateFormat.SYSTEM]): `Sat, 14 March 2026, 10:00:00 (+01:00)`.
	 *
	 * @param instant Point in time to format.
	 * @param timeZone Optional timezone override; defaults to [TimeZone.currentSystemDefault].
	 * @param dateFormat Date portion style; defaults to [DateFormat.SYSTEM].
	 */
	fun formatDateTime(
		instant: Instant,
		timeZone: TimeZone = TimeZone.currentSystemDefault(),
		dateFormat: DateFormat = DateFormat.SYSTEM,
	): String {
		val local = instant.toLocalDateTime(timeZone)
		val offset = instant.offsetIn(timeZone)
		return "${dateFormat.pattern.format(local.date)}, ${TIME_FORMAT.format(local.time)} ($offset)"
	}

	/**
	 * Format an [Instant] as a date-only string in the system default timezone.
	 *
	 * Example output (with [DateFormat.SYSTEM]): `Sat, 14 March 2026`.
	 *
	 * @param instant Point in time to format.
	 * @param timeZone Optional timezone override; defaults to [TimeZone.currentSystemDefault].
	 * @param dateFormat Date style; defaults to [DateFormat.SYSTEM].
	 */
	fun formatDate(
		instant: Instant,
		timeZone: TimeZone = TimeZone.currentSystemDefault(),
		dateFormat: DateFormat = DateFormat.SYSTEM,
	): String = dateFormat.pattern.format(instant.toLocalDateTime(timeZone).date)
}

/**
 * Format this [Instant] as a full date-time string in the system default timezone.
 *
 * @see InstantFormatter.formatDateTime
 */
fun Instant.formatDateTime(
	timeZone: TimeZone = TimeZone.currentSystemDefault(),
	dateFormat: DateFormat = DateFormat.SYSTEM,
): String = InstantFormatter.formatDateTime(this, timeZone, dateFormat)

/**
 * Format this [Instant] as a date-only string in the system default timezone.
 *
 * @see InstantFormatter.formatDate
 */
fun Instant.formatDate(
	timeZone: TimeZone = TimeZone.currentSystemDefault(),
	dateFormat: DateFormat = DateFormat.SYSTEM,
): String = InstantFormatter.formatDate(this, timeZone, dateFormat)
