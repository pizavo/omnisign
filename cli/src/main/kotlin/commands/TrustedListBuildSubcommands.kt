package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.data.service.TrustedListCompiler
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import cz.pizavo.omnisign.extensions.*
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * CLI command group for the interactive TL builder workflow.
 *
 * The primary entry point is `create`, which guides the user through the entire
 * draft in a single interactive session.  The remaining subcommands allow
 * non-interactive editing of an existing draft after the fact.
 */
class TrustedListBuild : CliktCommand(name = "build") {
	override fun help(context: Context): String =
		"Build a custom trusted list interactively or edit an existing draft"
	
	override fun run() = Unit
}

/**
 * Create the `config tl build` command with all subcommands registered.
 */
fun trustedListBuildCommand() = TrustedListBuild().subcommands(
	TrustedListBuildCreate(),
	TrustedListBuildShow(),
	TrustedListBuildAddTsp(),
	TrustedListBuildRemoveTsp(),
	TrustedListBuildAddService(),
	TrustedListBuildRemoveService(),
	TrustedListBuildCompile(),
	TrustedListBuildDelete()
)

private val SERVICE_TYPE_HINTS = listOf(
	"http://uri.etsi.org/TrstSvc/Svctype/CA/QC" to "CA/QC      — Qualified CA",
	"http://uri.etsi.org/TrstSvc/Svctype/CA/PKC" to "CA/PKC     — Non-qualified CA",
	"http://uri.etsi.org/TrstSvc/Svctype/TSA/QTST" to "TSA/QTST   — Qualified timestamp authority",
	"http://uri.etsi.org/TrstSvc/Svctype/TSA" to "TSA         — Non-qualified timestamp authority",
	"http://uri.etsi.org/TrstSvc/Svctype/EDS/Q" to "EDS/Q       — Qualified electronic delivery",
	"http://uri.etsi.org/TrstSvc/Svctype/OCSP/QC" to "OCSP/QC     — Qualified OCSP",
)

private val SERVICE_STATUS_HINTS = listOf(
	"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted" to "granted    — Active / granted",
	"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn" to "withdrawn  — Withdrawn",
	"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/recognisedatnationallevel" to "recognised — Recognised at national level",
	"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/deprecatedatnationallevel" to "deprecated — Deprecated at national level",
)

/**
 * Interactive wizard that builds an entire [CustomTrustedListDraft] in a single session.
 *
 * The user is guided step by step:
 * 1. Draft metadata (territory, scheme operator name)
 * 2. One or more Trust Service Providers — name, optional trade name and info URL
 * 3. For each TSP: one or more services — name, type URI, status URI, signing cert
 * 4. Optional immediate compilation and registration as a trusted list source
 *
 * Required fields are re-prompted until a non-blank answer is given.
 * Progress is persisted after each completed TSP so the session can be
 * resumed via the non-interactive subcommands.  If the user presses Ctrl+C
 * at any point the in-progress draft is deleted and the process exits cleanly.
 */
class TrustedListBuildCreate : CliktCommand(name = "create"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	private val compiler: TrustedListCompiler by inject()
	
	private val name by argument(help = "Draft name (also used as the default output file stem)")

	private val profile by option(
		"--profile", "-p",
		help = "When registering the compiled TL, store it in this profile instead of the global config"
	)
	
	override fun help(context: Context): String =
		"Interactively build a complete trusted list draft in one guided session"
	
	override fun run(): Unit = runBlocking {
		val t = terminal
		var draftCreated = false
		
		val shutdownHook = Thread(Thread.currentThread().threadGroup, {
			if (draftCreated) {
				runBlocking { manageTl.deleteDraft(name) }
			}
		}, "tl-wizard-cleanup")
		Runtime.getRuntime().addShutdownHook(shutdownHook)
		
		t.println("\n╔══════════════════════════════════════════════════════════╗")
			t.println("║     Trusted List Builder — interactive wizard            ║")
			t.println("╚══════════════════════════════════════════════════════════╝")
			t.println("  Draft name : $name")
			t.println("  Press Ctrl+C at any time to cancel and discard the draft.\n")
			
			t.println("── Step 1: Scheme information ───────────────────────────────")
			val territory = t.promptRequired(
				"Territory (ISO 3166-1 alpha-2, e.g. CZ)",
				default = "XX"
			).uppercase().take(2)
			
			val operatorName = t.promptRequired(
				"Scheme operator name",
				hint = "The organisation publishing this trusted list"
			)
			
			var draft = CustomTrustedListDraft(name = name, territory = territory, schemeOperatorName = operatorName)
			manageTl.upsertDraft(draft).onLeft { e ->
				echo("❌ Failed to save draft: ${e.message}", err = true); return@runBlocking
			}
			draftCreated = true
			
			t.println("\n── Step 2: Trust Service Providers ──────────────────────────")
			var addAnotherTsp = t.confirm("Add a Trust Service Provider?", default = true)
			
			while (addAnotherTsp) {
				t.println("\n  ── New TSP ──────────────────────────────────────────────")
				val tspName = t.promptRequired("  TSP official name")
				val tradeName = t.promptOptional("  TSP trade/brand name", hint = "(optional — press Enter to skip)")
				val infoUrl = t.promptOptional("  TSP information URL", hint = "(optional — homepage or registry entry)")
					?: ""
				
				val services = mutableListOf<TrustServiceDraft>()
				
				t.println("\n  ── Services for '$tspName' ──────────────────────────────")
				var addAnotherService = t.confirm("  Add a service to '$tspName'?", default = true)
				
				while (addAnotherService) {
					t.println("\n    ── New service ────────────────────────────────────────")
					val svcName = t.promptRequired("    Service name")
					val typeId = t.promptUriWithHints("Service type identifier", SERVICE_TYPE_HINTS)
					val status = t.promptUriWithHints("Service status", SERVICE_STATUS_HINTS)
					val certPath = t.promptCertPath()
					
					services += TrustServiceDraft(
						name = svcName,
						typeIdentifier = typeId,
						status = status,
						certificatePath = certPath
					)
					t.println("    ✔ Service '$svcName' added.")
					addAnotherService = t.confirm("  Add another service to '$tspName'?", default = false)
				}
				
				manageTl.upsertTsp(name, TrustServiceProviderDraft(tspName, tradeName, infoUrl, services)).fold(
					ifLeft = { e -> echo("❌ Failed to save TSP: ${e.message}", err = true) },
					ifRight = { t.println("\n  ✅ TSP '$tspName' saved (${services.size} service(s)).") }
				)
				
				addAnotherTsp = t.confirm("\nAdd another Trust Service Provider?", default = false)
			}
			
			manageTl.getDraft(name).onRight { draft = it }
			
			t.println("\n── Draft summary ────────────────────────────────────────────")
			t.println("  Name      : ${draft.name}")
			t.println("  Territory : ${draft.territory}")
			t.println("  Operator  : ${draft.schemeOperatorName}")
			t.println("  TSPs      : ${draft.trustServiceProviders.size}")
			draft.trustServiceProviders.forEach { tsp ->
				t.println("    ● ${tsp.name} — ${tsp.services.size} service(s)")
			}
			
			t.println("")
			if (!t.confirm("Compile to XML now?", default = true)) {
				t.println("\n✅ Draft '$name' saved. Compile later with:")
				t.println("   config tl build compile $name --out $name.xml")
				Runtime.getRuntime().removeShutdownHook(shutdownHook)
				return@runBlocking
			}
			
			val defaultOut = "$name.xml"
			val outPathRaw = t.promptOptional("Output file path", hint = "(press Enter for default: $defaultOut)")
				?: defaultOut
			val outputFile = File(outPathRaw)
			
			runCatching { compiler.compileTo(draft, outputFile) }.fold(
				onFailure = { e -> echo("❌ Compilation failed: ${e.message}", err = true); return@runBlocking },
				onSuccess = { t.println("\n✅ Trusted list written to: ${outputFile.absolutePath}") }
			)
			
		if (t.confirm("Register this file as a custom trusted list source?", default = true)) {
			val scope = profile?.let { "profile '$it'" } ?: "global config"
			manageTl.addTrustedList(CustomTrustedListConfig(name, outputFile.toURI().toString()), profile).fold(
				ifLeft = { e -> echo("❌ Failed to register: ${e.message}", err = true) },
				ifRight = {
					t.println("✅ Registered as trusted list '$name' in $scope.")
					t.println("⚠️ No signing certificate — TL signature will not be verified.")
					t.println("   To add one later: config tl add --name $name --source ${outputFile.absolutePath} --signing-cert <cert>")
				}
			)
		} else {
			t.println("   To register later: config tl add --name $name --source ${outputFile.absolutePath}")
		}
			
			Runtime.getRuntime().removeShutdownHook(shutdownHook)
	}
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

/**
 * Add or replace a Trust Service Provider in a draft (non-interactive).
 */
class TrustedListBuildAddTsp : CliktCommand(name = "add-tsp"), KoinComponent {
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
				echo("   Add services with: config tl build add-service $draftName \"$tspName\" ...")
			}
		)
	}
}

/**
 * Remove a Trust Service Provider (and all its services) from a draft.
 */
class TrustedListBuildRemoveTsp : CliktCommand(name = "remove-tsp"), KoinComponent {
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

/**
 * Add or replace a trust service under a TSP in a draft (non-interactive).
 */
class TrustedListBuildAddService : CliktCommand(name = "add-service"), KoinComponent {
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

/**
 * Remove a trust service from a TSP inside a draft.
 */
class TrustedListBuildRemoveService : CliktCommand(name = "remove-service"), KoinComponent {
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

/**
 * Compile a TL builder draft into an ETSI TS 119612 XML file and optionally
 * register it as a custom trusted list source.
 */
class TrustedListBuildCompile : CliktCommand(name = "compile"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	private val compiler: TrustedListCompiler by inject()
	
	private val draftName by argument(help = "Draft name to compile")
	private val out by option(
		"--out", "-o",
		help = "Output path for the generated XML file (default: <draft-name>.xml in the current directory)"
	).path(canBeDir = false)
	private val register by option(
		"--register",
		help = "After compiling, automatically register the output file as a custom TL source"
	).flag(default = false)
	private val profile by option(
		"--profile", "-p",
		help = "When --register is used, store the TL in this profile instead of the global config"
	)
	
	override fun help(context: Context): String = "Compile a TL draft into an ETSI TS 119612 XML file"
	
	override fun run(): Unit = runBlocking {
		manageTl.getDraft(draftName).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { draft ->
				val outputFile = out?.toFile() ?: File("$draftName.xml")
				runCatching { compiler.compileTo(draft, outputFile) }
					.onFailure { e -> echo("❌ Compilation failed: ${e.message}", err = true); return@runBlocking }
				echo("✅ Trusted list written to: ${outputFile.absolutePath}")
				if (register) {
					val scope = profile?.let { "profile '$it'" } ?: "global config"
					manageTl.addTrustedList(
						CustomTrustedListConfig(draftName, outputFile.toURI().toString()),
						profile
					).fold(
						ifLeft = { error -> echo("❌ Failed to register: ${error.message}", err = true) },
						ifRight = {
							echo("✅ Registered as trusted list '$draftName' in $scope.")
							echo("⚠️ No signing certificate — TL signature will not be verified.")
						}
					)
				} else {
					echo("   To register it: config tl add --name $draftName --source ${outputFile.absolutePath}")
				}
			}
		)
	}
}

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
