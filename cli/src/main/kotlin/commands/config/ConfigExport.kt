package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
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
import kotlin.io.path.writeText

/**
 * CLI subcommand that exports the global configuration section to a file.
 *
 * Usage examples:
 * ```
 * omnisign config export global.json
 * omnisign config export global.yaml --format yaml
 * omnisign config export global.xml
 * ```
 */
class ConfigExport : CliktCommand(name = "export"), KoinComponent {
	private val exportImport: ExportImportConfigUseCase by inject()
	
	private val outputFile by argument(
		help = "Destination file path. The format is inferred from the file extension when --format is omitted."
	)
	
	private val format by option(
		"--format", "-f",
		help = "Export format (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Inferred from the output file extension when omitted."
	).enum<ConfigFormat>()
	
	private val all by option(
		"--all", "-a",
		help = "Export the full application configuration instead of only the global section."
	).flag()
	
	override fun help(context: Context): String =
		"Export the global (or full) configuration to a file"
	
	override fun run(): Unit = runBlocking {
		val resolvedFormat = resolveFormat(outputFile, format) ?: return@runBlocking
		val result = if (all) exportImport.exportApp(resolvedFormat)
		else exportImport.exportGlobal(resolvedFormat)
		
		result.fold(
			ifLeft = { error ->
				echo("❌ Export failed: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
			},
			ifRight = { text ->
				Path.of(outputFile).writeText(text)
				val scope = if (all) "full application" else "global"
				echo("✅ $scope configuration exported to $outputFile (${resolvedFormat.name})")
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

