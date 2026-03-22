package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.data.service.TrustedListCompiler
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import cz.pizavo.omnisign.extensions.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

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
	
	@OptIn(ExperimentalAtomicApi::class)
	override fun run(): Unit = runBlocking {
		val t = terminal
		val draftCreated = AtomicBoolean(false)
		
		val shutdownHook = thread(start = false, name = "tl-wizard-cleanup") {
			if (draftCreated.load()) {
				runBlocking { manageTl.deleteDraft(name) }
			}
		}
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
		draftCreated.store(true)
		
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
		Runtime.getRuntime().removeShutdownHook(shutdownHook)
		
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
	}
	
	private companion object {
		val SERVICE_TYPE_HINTS = listOf(
			"http://uri.etsi.org/TrstSvc/Svctype/CA/QC" to "CA/QC      — Qualified CA",
			"http://uri.etsi.org/TrstSvc/Svctype/CA/PKC" to "CA/PKC     — Non-qualified CA",
			"http://uri.etsi.org/TrstSvc/Svctype/TSA/QTST" to "TSA/QTST   — Qualified timestamp authority",
			"http://uri.etsi.org/TrstSvc/Svctype/TSA" to "TSA         — Non-qualified timestamp authority",
			"http://uri.etsi.org/TrstSvc/Svctype/EDS/Q" to "EDS/Q       — Qualified electronic delivery",
			"http://uri.etsi.org/TrstSvc/Svctype/OCSP/QC" to "OCSP/QC     — Qualified OCSP",
		)
		
		val SERVICE_STATUS_HINTS = listOf(
			"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted" to "granted    — Active / granted",
			"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn" to "withdrawn  — Withdrawn",
			"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/recognisedatnationallevel" to "recognised — Recognised at national level",
			"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/deprecatedatnationallevel" to "deprecated — Deprecated at national level",
		)
	}
}

