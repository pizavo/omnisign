package cz.pizavo.omnisign.domain.model.value

import kotlinx.serialization.Serializable

/**
 * User-selectable formatting preferences that apply across every surface — the CLI, the desktop
 * GUI, and the web client.
 *
 * Persisted as `preferences/format.json` in the application configuration directory and read wherever
 * the app renders dates for the user, so a format chosen in one surface is honoured by the others
 * (a date format set via the CLI's `config date-format` is the same value the desktop reads). Grouping
 * the choice in a dedicated type — rather than a bare [DateFormat] — leaves room for future number and
 * time formatting options without changing the on-disk schema's shape.
 *
 * @property dateFormat Date-display style applied to all rendered dates. Defaults to [DateFormat.SYSTEM].
 */
@Serializable
data class FormatPreferences(
	val dateFormat: DateFormat = DateFormat.SYSTEM,
)
