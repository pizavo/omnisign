package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.model.config.TrustedListAddress
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Render an address on one line, or say it is missing.
 *
 * A draft is routinely shown mid-authoring, so an absent address is expected rather than an error —
 * but it does stop the list compiling, which is why it is spelled out rather than left blank.
 */
private fun TrustedListAddress?.describe(): String = when (this) {
	null -> "(not set — required to compile)"
	else -> listOfNotNull(
		streetAddress.takeIf { it.isNotBlank() },
		postalCode?.takeIf { it.isNotBlank() },
		locality.takeIf { it.isNotBlank() },
		stateOrProvince?.takeIf { it.isNotBlank() },
		countryName.takeIf { it.isNotBlank() },
		electronicAddress.takeIf { it.isNotBlank() },
	).joinToString(", ").ifBlank { "(incomplete)" }
}

/**
 * Show the current state of a TL builder draft.
 */
class TrustedListBuildShow : CliktCommand(name = "show"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val name by argument(help = "Draft name")
	
	override fun help(context: Context): String = "Show the contents of a TL builder draft"
	
	override fun run(): Unit = runBlocking {
		manageTl.getDraft(name).fold(
			ifLeft = { error ->
				echo("❌ ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
			},
			ifRight = { draft ->
				echo("Draft          : ${draft.name}")
				echo("Territory      : ${draft.territory}")
				echo("Scheme name    : ${draft.schemeName.ifBlank { "(not set)" }}")
				echo("Scheme info URI: ${draft.schemeInformationUri.ifBlank { "(not set)" }}")
				echo("Scheme operator: ${draft.schemeOperatorName.ifBlank { "(not set)" }}")
				echo("Operator addr. : ${draft.schemeOperatorAddress.describe()}")
				echo("Status approach: ${draft.statusDeterminationApproach}")
				echo("History period : ${draft.historicalInformationPeriod} day(s)")
				if (draft.trustServiceProviders.isEmpty()) {
					echo("TSPs           : (none)")
				} else {
					echo("TSPs:")
					draft.trustServiceProviders.forEach { tsp ->
						echo("  ● ${tsp.name}${tsp.tradeName?.let { " ($it)" } ?: ""}")
						echo("    Info URL : ${tsp.infoUrl.ifBlank { "(not set)" }}")
						echo("    Address  : ${tsp.address.describe()}")
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

