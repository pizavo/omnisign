package cz.pizavo.omnisign.commands.config.tl.build.tsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Remove a Trust Service Provider (and all its services) from a draft.
 */
class TrustedListBuildTspRemove : CliktCommand(name = "remove"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val draftName by argument(help = "Draft name")
	private val tspName by argument(help = "TSP name to remove")
	
	override fun help(context: Context): String =
		"Remove a Trust Service Provider and all its services from a draft"
	
	override fun run(): Unit = runBlocking {
		manageTl.removeTsp(draftName, tspName).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { echo("✅ TSP '$tspName' removed from draft '$draftName'.") }
		)
	}
}



