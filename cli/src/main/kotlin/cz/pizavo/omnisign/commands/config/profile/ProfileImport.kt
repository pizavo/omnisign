package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.usecase.ExportImportConfigUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * CLI subcommand that imports a profile from a file and upserts it into the configuration.
 *
 * Usage examples:
 * ```
 * omnisign config profile import my-profile.json
 * omnisign config profile import my-profile.yaml --name renamed-profile
 * omnisign config profile import profile.xml --format xml
 * ```
 */
class ProfileImport : CliktCommand(name = "import"), KoinComponent {
	private val exportImport: ExportImportConfigUseCase by inject()
	
	private val inputFile by argument(
		help = "Source file path. The format is inferred from the file extension when --format is omitted."
	)
	
	private val format by option(
		"--format", "-f",
		help = "Import format (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Inferred from the input file extension when omitted."
	).enum<ConfigFormat>()
	
	private val name by option(
		"--name", "-n",
		help = "Override the profile name from the file. If omitted, the name embedded in the file is used."
	)
	
	override fun help(context: Context): String =
		"Import a profile from a file"
	
	override fun run(): Unit = runBlocking {
		val resolvedFormat = resolveFormat(inputFile, format) ?: return@runBlocking
		val text = runCatching { Path.of(inputFile).readText() }.getOrElse { e ->
			echo("❌ Cannot read file '$inputFile': ${e.message}", err = true)
			return@runBlocking
		}
		
		exportImport.importProfile(text, resolvedFormat, name).fold(
			ifLeft = { error ->
				echo("❌ Import failed: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
			},
			ifRight = { savedName ->
				echo("✅ Profile '$savedName' imported from $inputFile (${resolvedFormat.name})")
			}
		)
	}
	
	private fun resolveFormat(filePath: String, explicit: ConfigFormat?): ConfigFormat? {
		if (explicit != null) return explicit
		val extension = filePath.substringAfterLast('.', "")
		val inferred = ConfigFormat.fromExtension(extension)
		if (inferred == null) {
			echo(
				"❌ Cannot infer format from extension '.$extension'. " +
						"Use --format to specify it explicitly.",
				err = true
			)
		}
		return inferred
	}
}

