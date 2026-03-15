package cz.pizavo.omnisign.commands.config.tl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for removing a registered custom trusted list source by name.
 *
 * When [profile] is specified, removes the entry from that profile's validation config;
 * otherwise removes it from the global config.
 */
class TrustedListRemover : CliktCommand(name = "remove"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val name by argument(help = "Name of the trusted list to remove")
	
	private val profile by option(
		"--profile", "-p",
		help = "Remove the trusted list from the given profile instead of the global config"
	)
	
	override fun help(context: Context): String =
		"Remove a registered custom trusted list source"
	
	override fun run(): Unit = runBlocking {
		manageTl.removeTrustedList(name, profile).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = {
				val scope = profile?.let { "profile '$it'" } ?: "global config"
				echo("✅ Trusted list '$name' removed from $scope.")
			}
		)
	}
}

