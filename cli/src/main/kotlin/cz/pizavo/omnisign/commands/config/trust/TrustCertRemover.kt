package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for removing a directly trusted certificate by name.
 *
 * When [profile] is specified, removes the entry from that profile's validation config;
 * otherwise removes it from the global config.
 */
class TrustCertRemover : CliktCommand(name = "remove"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val name by argument(help = "Name of the trusted certificate to remove")
	
	private val profile by option(
		"--profile", "-p",
		help = "Remove from the given profile instead of the global config"
	)
	
	override fun help(context: Context): String =
		"Remove a directly trusted certificate"
	
	override fun run(): Unit = runBlocking {
		manageTl.removeTrustedCertificate(name, profile).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = {
				val scope = profile?.let { "profile '$it'" } ?: "global config"
				echo("✅ Trusted certificate '$name' removed from $scope.")
			}
		)
	}
}

