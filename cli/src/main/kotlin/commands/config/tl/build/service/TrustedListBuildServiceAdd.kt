package cz.pizavo.omnisign.commands.config.tl.build.service

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Add or replace a trust service under a TSP in a draft (non-interactive).
 */
class TrustedListBuildServiceAdd : CliktCommand(name = "add"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val draftName by argument(help = "Draft name")
	private val tspName by argument(help = "TSP name")
	private val serviceName by option("--name", "-n", help = "Human-readable name of the service").required()
	private val typeId by option(
		"--type-id",
		help = "Service type identifier URI (e.g. http://uri.etsi.org/TrstSvc/Svctype/CA/QC)"
	).required()
	private val status by option(
		"--status",
		help = "Service status URI (e.g. http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted)"
	).required()
	private val cert by option(
		"--cert",
		help = "Path to the PEM or DER certificate representing this service's digital identity"
	).path(mustExist = true, canBeDir = false, mustBeReadable = true).required()
	
	override fun help(context: Context): String = "Add or replace a trust service under a TSP in a draft"
	
	override fun run(): Unit = runBlocking {
		manageTl.upsertService(draftName, tspName, TrustServiceDraft(serviceName, typeId, status, cert.toString()))
			.fold(
				ifLeft = { error -> echo("❌ ${error.message}", err = true) },
				ifRight = { echo("✅ Service '$serviceName' added to TSP '$tspName' in draft '$draftName'.") }
			)
	}
}

