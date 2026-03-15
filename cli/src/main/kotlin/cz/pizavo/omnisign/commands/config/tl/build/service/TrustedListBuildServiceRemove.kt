package cz.pizavo.omnisign.commands.config.tl.build.service

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Remove a trust service from a TSP inside a draft.
 */
class TrustedListBuildServiceRemove : CliktCommand(name = "remove"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val draftName by argument(help = "Draft name")
	private val tspName by argument(help = "TSP name")
	private val serviceName by argument(help = "Service name to remove")
	
	override fun help(context: Context): String = "Remove a trust service from a TSP inside a draft"
	
	override fun run(): Unit = runBlocking {
		manageTl.removeService(draftName, tspName, serviceName).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { echo("✅ Service '$serviceName' removed from TSP '$tspName'.") }
		)
	}
}

