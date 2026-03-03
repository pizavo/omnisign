package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Delete a TL builder draft without producing any XML output.
 */
class TrustedListBuildDelete : CliktCommand(name = "delete"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val name by argument(help = "Draft name to delete")
	
	override fun help(context: Context): String =
		"Delete a TL builder draft (does not affect any already-compiled XML files)"
	
	override fun run(): Unit = runBlocking {
		manageTl.deleteDraft(name).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { echo("✅ Draft '$name' deleted.") }
		)
	}
}

