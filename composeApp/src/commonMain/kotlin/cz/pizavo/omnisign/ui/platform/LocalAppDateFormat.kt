package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import kotlin.time.Instant

/**
 * The active UI date format.
 *
 * Backed by a `staticCompositionLocalOf`, so changing the provided value recomposes the whole scope
 * and every [formattedDateTime] / [formattedDate] call re-renders in the new format — without
 * disposing the subtree, so UI and ViewModel state survive the switch.
 */
val LocalAppDateFormat = staticCompositionLocalOf { DateFormat.SYSTEM }

/** Format this instant as a full date-time string using the user's selected [LocalAppDateFormat]. */
@Composable
fun Instant.formattedDateTime(): String = formatDateTime(dateFormat = LocalAppDateFormat.current)

/** Format this instant as a date-only string using the user's selected [LocalAppDateFormat]. */
@Composable
fun Instant.formattedDate(): String = formatDate(dateFormat = LocalAppDateFormat.current)
