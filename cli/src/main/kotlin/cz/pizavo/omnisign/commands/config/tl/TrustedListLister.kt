package cz.pizavo.omnisign.commands.config.tl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for listing all registered custom trusted list sources.
 *
 * When [profile] is specified, lists only the TLs registered in that profile;
 * otherwise lists the global TLs.
 */
class TrustedListLister : CliktCommand(name = "list"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val profile by option(
		"--profile", "-p",
		help = "List trusted lists from the given profile instead of the global config"
	)
	
	override fun help(context: Context): String =
		"List all registered custom trusted list sources"
	
	override fun run(): Unit = runBlocking {
		manageTl.listTrustedLists(profile).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { lists ->
				val scope = profile?.let { "profile '$it'" } ?: "global config"
				if (lists.isEmpty()) {
					echo("No custom trusted lists registered in $scope. Add one with: config tl add --name <n> --source <url>")
				} else {
					echo("Custom trusted lists ($scope):")
					lists.forEach { tl ->
						echo("  ● ${tl.name}")
						echo("    Source      : ${tl.source}")
						tl.signingCertPath?.let { echo("    Signing cert: $it") }
							?: echo("    Signing cert: (none — signature not verified)")
					}
				}
			}
		)
	}
}

