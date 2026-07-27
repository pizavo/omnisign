package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for listing all directly trusted certificates in a scope.
 */
class TrustCertLister : CliktCommand(name = "list"), KoinComponent {
	private val trustStore: TrustStore by inject()

	private val profile by option(
		"--profile", "-p",
		help = "List certificates from the given profile instead of the global scope"
	)

	override fun help(context: Context): String =
		"List all directly trusted certificates"

	override fun run(): Unit = runBlocking {
		val scope = TrustScope.of(profile)
		val scopeLabel = profile?.let { "profile '$it'" } ?: "global scope"
		trustStore.list(scope).fold(
			ifLeft = { error ->
				echo("❌ ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
			},
			ifRight = { certs ->
				if (certs.isEmpty()) {
					echo("No trusted certificates in $scopeLabel. Add one with: config trust add --cert <file>")
				} else {
					echo("Trusted certificates ($scopeLabel):")
					certs.forEach { c ->
						echo("● ${c.subjectDN} [${c.type}]")
						echo("  expires ${c.notAfter}")
						echo("  ${c.fingerprint}")
					}
				}
			}
		)
	}
}
