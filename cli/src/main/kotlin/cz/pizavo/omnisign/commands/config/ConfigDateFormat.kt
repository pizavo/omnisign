package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.enum
import cz.pizavo.omnisign.data.preferences.loadFormatPreferences
import cz.pizavo.omnisign.data.preferences.saveFormatPreferences
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.FormatPreferences
import cz.pizavo.omnisign.domain.model.value.formatDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * CLI subcommand for viewing or setting the date-display format used in CLI output.
 *
 * The preference is persisted to `preferences/format.json` — the same cross-surface store the desktop
 * application reads — so a format chosen here is honoured by both surfaces. Invoked without an
 * argument, it prints the current format and a table of every available format with its pattern and a
 * worked example.
 */
class ConfigDateFormat : CliktCommand(name = "date-format") {

	private val format by argument(
		name = "FORMAT",
		help = "Date format to set; omit to list all formats with examples",
	).enum<DateFormat>().optional()

	override fun help(context: Context): String =
		"Get or set the date format used in CLI output"

	override fun run() {
		val target = format
		if (target == null) {
			val current = loadFormatPreferences().dateFormat
			echo("Current date format: ${current.name} (${current.displayPattern})")
			echo("")
			echo("Available formats:")
			val nameWidth = DateFormat.entries.maxOf { it.name.length }
			val patternWidth = DateFormat.entries.maxOf { it.displayPattern.length }
			DateFormat.entries.forEach { entry ->
				val example = EXAMPLE_DATE.formatDate(TimeZone.UTC, entry)
				echo("  ${entry.name.padEnd(nameWidth)}  ${entry.displayPattern.padEnd(patternWidth)}  ($example)")
			}
			echo("")
			echo("Set with: config date-format <FORMAT>")
		} else {
			saveFormatPreferences(FormatPreferences(target))
			echo("✅ Date format set to ${target.name} (${target.displayPattern}).")
		}
	}

	companion object {
		/** Reference date — 24 January 2000 — rendered to show a worked example of each format. */
		private val EXAMPLE_DATE = Instant.parse("2000-01-24T00:00:00Z")
	}
}
