package cz.pizavo.omnisign.commands.config.tl.build.tsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.model.config.TrustedListAddress
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
	private val street by option("--street", help = "TSP street address (ETSI TS 119612 clause 5.4.4)")
	private val locality by option("--locality", help = "TSP town or city")
	private val country by option("--country", help = "TSP country name or ISO 3166-1 code")
	private val stateOrProvince by option("--state", help = "Optional TSP state or province")
	private val postalCode by option("--postal-code", help = "Optional TSP postal code")
	private val electronicAddress by option(
		"--electronic-address",
		help = "TSP contact URI, e.g. mailto:info@tsp.example or https://tsp.example/contact"
	)

	override fun help(context: Context): String = "Add or replace a Trust Service Provider in a draft"

	/**
	 * Assemble the supplied address options, or `null` when none was given at all.
	 *
	 * A partially supplied address is kept rather than rejected here: the draft is an in-progress
	 * definition, and [cz.pizavo.omnisign.domain.port.TrustedListCompilerPort] names every gap when
	 * the list is eventually compiled.
	 */
	private fun address(): TrustedListAddress? {
		val supplied = listOfNotNull(street, locality, country, stateOrProvince, postalCode, electronicAddress)
		if (supplied.isEmpty()) return null
		return TrustedListAddress(
			streetAddress = street ?: "",
			locality = locality ?: "",
			countryName = country ?: "",
			stateOrProvince = stateOrProvince,
			postalCode = postalCode,
			electronicAddress = electronicAddress ?: "",
		)
	}

	override fun run(): Unit = runBlocking {
		val tsp = TrustServiceProviderDraft(
			name = tspName,
			tradeName = tradeName,
			address = address(),
			infoUrl = infoUrl ?: "",
		)
		manageTl.upsertTsp(draftName, tsp).fold(
			ifLeft = { error ->
				echo("❌ ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
			},
			ifRight = {
				echo("✅ TSP '$tspName' added to draft '$draftName'.")
				echo("   Add services with: config tl build service add $draftName \"$tspName\" ...")
			}
		)
	}
}



