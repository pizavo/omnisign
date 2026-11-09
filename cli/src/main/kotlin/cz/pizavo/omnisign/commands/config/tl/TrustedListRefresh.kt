package cz.pizavo.omnisign.commands.config.tl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import cz.pizavo.omnisign.data.preferences.loadFormatPreferences
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.domain.port.TrustedListRefreshPort
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand that forces an immediate online refresh of every trusted list
 * (EU LOTL plus every custom list) into the shared on-disk cache.
 *
 * Useful as a maintenance step so subsequent CLI validations — and a running
 * desktop app, on its next cycle — start from a freshly refreshed cache instead
 * of paying the download on a validation's critical path.
 *
 * When a list could not be refreshed, or the refresh could not run at all, the command
 * says so on stderr and exits with status 1.
 */
class TrustedListRefresh : CliktCommand(name = "refresh"), KoinComponent {
	private val refreshPort: TrustedListRefreshPort by inject()

	override fun help(context: Context): String =
		"Refresh the EU LOTL and custom trusted lists now (updates the shared cache)"

	override fun run(): Unit = runBlocking {
		echo("Refreshing trusted lists…")
		val failure = try {
			refreshPort.refreshNow()
		} catch (e: Exception) {
			echo("❌ Could not refresh the trusted lists: ${e.message ?: e::class.simpleName}", err = true)
			throw ProgramResult(1)
		}
		failure?.let { reportFailure(it) }
		val at = refreshPort.lastRefreshAt.value
		val dateFormat = loadFormatPreferences().dateFormat
		echo("✅ Trusted lists refreshed${at?.let { " at ${it.formatDateTime(dateFormat = dateFormat)}" } ?: ""}.")
	}

	/**
	 * Name the lists in [failure] on stderr and end the command with status 1.
	 */
	private fun reportFailure(failure: TrustedListRefreshFailure): Nothing {
		when (failure) {
			is TrustedListRefreshFailure.EuLotl -> echo("❌ The $EU_LOTL_NAME could not be refreshed.", err = true)
			is TrustedListRefreshFailure.CustomList ->
				echo("❌ The trusted list '${failure.name}' could not be refreshed.", err = true)
			is TrustedListRefreshFailure.EuLotlAndOthers -> reportSeveral(listOf(EU_LOTL_NAME) + failure.names)
			is TrustedListRefreshFailure.Multiple -> reportSeveral(failure.names)
		}
		throw ProgramResult(1)
	}

	/**
	 * Say on stderr how many lists could not be refreshed, then name each of [names] on a line
	 * of its own.
	 */
	private fun reportSeveral(names: List<String>) {
		echo("❌ ${names.size} trusted lists could not be refreshed:", err = true)
		names.forEach { echo("  • $it", err = true) }
	}

	private companion object {
		/** The name of the EU list of trusted lists in what the command prints. */
		const val EU_LOTL_NAME = "EU list of trusted lists (LOTL)"
	}
}
