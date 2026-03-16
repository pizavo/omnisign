package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for listing all stored TL builder drafts.
 *
 * Prints a summary line for every draft (name, territory, scheme-operator and TSP count).
 * Use `config tl build show <name>` to inspect a specific draft in detail.
 */
class TrustedListBuildList : CliktCommand(name = "list"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()

	override fun help(context: Context): String = "List all stored TL builder drafts"

	override fun run(): Unit = runBlocking {
		manageTl.listDrafts().fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { drafts ->
				if (drafts.isEmpty()) {
					echo("No TL builder drafts found. Create one with: config tl build create <name>")
				} else {
					echo("TL builder drafts (${drafts.size}):")
					drafts.values.forEach { draft ->
						echo("  ● ${draft.name}  [${draft.territory}]")
						echo("    Scheme operator: ${draft.schemeOperatorName.ifBlank { "(not set)" }}")
						echo("    TSPs           : ${draft.trustServiceProviders.size}")
					}
				}
			}
		)
	}
}

