package cz.pizavo.omnisign.commands.config.tl.build.tsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Add or replace a Trust Service Provider in a draft (non-interactive).
 */
class TrustedListBuildTspAdd : CliktCommand(name = "add"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val draftName by argument(help = "Draft name")
	private val tspName by option("--name", "-n", help = "Official name of the TSP").required()
	private val tradeName by option("--trade-name", help = "Optional trade/brand name of the TSP")
	private val infoUrl by option("--info-url", help = "URL pointing to the TSP's information page or registration")
	
	override fun help(context: Context): String = "Add or replace a Trust Service Provider in a draft"
	
	override fun run(): Unit = runBlocking {
		manageTl.upsertTsp(draftName, TrustServiceProviderDraft(tspName, tradeName, infoUrl ?: "")).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = {
				echo("✅ TSP '$tspName' added to draft '$draftName'.")
				echo("   Add services with: config tl build service add $draftName \"$tspName\" ...")
			}
		)
	}
}



