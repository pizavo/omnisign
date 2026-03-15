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
import kotlin.io.path.writeText

/**
 * CLI subcommand that exports a single named profile to a file.
 *
 * Usage examples:
 * ```
 * omnisign config profile export my-profile my-profile.json
 * omnisign config profile export my-profile my-profile.yaml
 * omnisign config profile export my-profile output.xml --format xml
 * ```
 */
class ProfileExport : CliktCommand(name = "export"), KoinComponent {
	private val exportImport: ExportImportConfigUseCase by inject()
	
	private val profileName by argument(help = "Name of the profile to export")
	
	private val outputFile by argument(
		help = "Destination file path. The format is inferred from the file extension when --format is omitted."
	)
	
	private val format by option(
		"--format", "-f",
		help = "Export format (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Inferred from the output file extension when omitted."
	).enum<ConfigFormat>()
	
	override fun help(context: Context): String =
		"Export a named profile to a file"
	
	override fun run(): Unit = runBlocking {
		val resolvedFormat = resolveFormat(outputFile, format) ?: return@runBlocking
		
		exportImport.exportProfile(profileName, resolvedFormat).fold(
			ifLeft = { error ->
				echo("❌ Export failed: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
			},
			ifRight = { text ->
				Path.of(outputFile).writeText(text)
				echo("✅ Profile '$profileName' exported to $outputFile (${resolvedFormat.name})")
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

