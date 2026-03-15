package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
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
 * CLI subcommand that imports a global or full application configuration from a file.
 *
 * Usage examples:
 * ```
 * omnisign config import global.json
 * omnisign config import global.yaml --format yaml
 * omnisign config import full-config.xml --all
 * ```
 */
class ConfigImport : CliktCommand(name = "import"), KoinComponent {
	private val exportImport: ExportImportConfigUseCase by inject()
	
	private val inputFile by argument(
		help = "Source file path. The format is inferred from the file extension when --format is omitted."
	)
	
	private val format by option(
		"--format", "-f",
		help = "Import format (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Inferred from the input file extension when omitted."
	).enum<ConfigFormat>()
	
	private val all by option(
		"--all", "-a",
		help = "Import as a full application configuration, replacing all sections."
	).flag()
	
	override fun help(context: Context): String =
		"Import a global (or full) configuration from a file"
	
	override fun run(): Unit = runBlocking {
		val resolvedFormat = resolveFormat(inputFile, format) ?: throw ProgramResult(1)
		val text = runCatching { Path.of(inputFile).readText() }.getOrElse { e ->
			echo("❌ Cannot read file '$inputFile': ${e.message}", err = true)
			throw ProgramResult(1)
		}
		
		val result = if (all) exportImport.importApp(text, resolvedFormat)
		else exportImport.importGlobal(text, resolvedFormat)
		
		result.fold(
			ifLeft = { error ->
				echo("❌ Import failed: ${error.message}", err = true)
				if (error.details != null) echo("Details: ${error.details}", err = true)
				throw ProgramResult(1)
			},
			ifRight = {
				val scope = if (all) "full application" else "global"
				echo("✅ $scope configuration imported from $inputFile (${resolvedFormat.name})")
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

