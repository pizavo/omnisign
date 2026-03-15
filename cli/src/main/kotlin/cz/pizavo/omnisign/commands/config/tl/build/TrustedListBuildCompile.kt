package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.data.service.TrustedListCompiler
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

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

