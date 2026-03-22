package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for listing all directly trusted certificates.
 *
 * When [profile] is specified, lists only the certificates registered in that profile;
 * otherwise lists the global certificates.
 */
class TrustCertLister : CliktCommand(name = "list"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val profile by option(
		"--profile", "-p",
		help = "List certificates from the given profile instead of the global config"
	)
	
	override fun help(context: Context): String =
		"List all directly trusted certificates"
	
	override fun run(): Unit = runBlocking {
		manageTl.listTrustedCertificates(profile).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { certs ->
				val scope = profile?.let { "profile '$it'" } ?: "global config"
				if (certs.isEmpty()) {
					echo("No trusted certificates in $scope. Add one with: config trust add --name <n> --cert <file>")
				} else {
					echo("Trusted certificates ($scope):")
					certs.forEach { c ->
						echo("  ● ${c.name}  [${c.type}]")
						echo("    Subject: ${c.subjectDN}")
					}
				}
			}
		)
	}
}
