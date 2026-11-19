package cz.pizavo.omnisign.commands.config.trust

import arrow.core.getOrElse
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.cli.failWith
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for listing all directly trusted certificates in a scope.
 *
 * A `--profile` that names no profile is refused rather than listed as empty.
 */
class TrustCertLister : CliktCommand(name = "list"), KoinComponent {
	private val trustStore: TrustStore by inject()
	private val manageProfile: ManageProfileUseCase by inject()

	private val profile by option(
		"--profile", "-p",
		help = "List certificates from the given profile, which must exist, instead of the global scope"
	)

	override fun help(context: Context): String =
		"List all directly trusted certificates"

	override fun run(): Unit = runBlocking {
		profile?.let { name -> manageProfile.get(name).getOrElse { failWith(it) } }
		val scope = TrustScope.of(profile)
		val scopeLabel = profile?.let { "profile '$it'" } ?: "global scope"
		trustStore.list(scope).fold(
			ifLeft = { failWith(it) },
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
