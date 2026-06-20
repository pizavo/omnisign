package cz.pizavo.omnisign.commands.config.tl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.data.preferences.loadFormatPreferences
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
 */
class TrustedListRefresh : CliktCommand(name = "refresh"), KoinComponent {
	private val refreshPort: TrustedListRefreshPort by inject()

	override fun help(context: Context): String =
		"Refresh the EU LOTL and custom trusted lists now (updates the shared cache)"

	override fun run(): Unit = runBlocking {
		echo("Refreshing trusted lists…")
		refreshPort.refreshNow()
		val at = refreshPort.lastRefreshAt.value
		val dateFormat = loadFormatPreferences().dateFormat
		echo("✅ Trusted lists refreshed${at?.let { " at ${it.formatDateTime(dateFormat = dateFormat)}" } ?: ""}.")
	}
}
