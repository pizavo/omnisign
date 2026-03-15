package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Show the current state of a TL builder draft.
 */
class TrustedListBuildShow : CliktCommand(name = "show"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val name by argument(help = "Draft name")
	
	override fun help(context: Context): String = "Show the contents of a TL builder draft"
	
	override fun run(): Unit = runBlocking {
		manageTl.getDraft(name).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { draft ->
				echo("Draft          : ${draft.name}")
				echo("Territory      : ${draft.territory}")
				echo("Scheme operator: ${draft.schemeOperatorName.ifBlank { "(not set)" }}")
				if (draft.trustServiceProviders.isEmpty()) {
					echo("TSPs           : (none)")
				} else {
					echo("TSPs:")
					draft.trustServiceProviders.forEach { tsp ->
						echo("  ● ${tsp.name}${tsp.tradeName?.let { " ($it)" } ?: ""}")
						echo("    Info URL : ${tsp.infoUrl.ifBlank { "(not set)" }}")
						if (tsp.services.isEmpty()) {
							echo("    Services : (none)")
						} else {
							tsp.services.forEach { svc ->
								echo("    ▸ ${svc.name}")
								echo("      Type  : ${svc.typeIdentifier}")
								echo("      Status: ${svc.status}")
								echo("      Cert  : ${svc.certificatePath}")
							}
						}
					}
				}
			}
		)
	}
}

