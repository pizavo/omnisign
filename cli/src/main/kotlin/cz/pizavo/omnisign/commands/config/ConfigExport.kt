package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.usecase.ConfigArchiveUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * CLI subcommand that exports the global (or full) configuration, together with its directly
 * trusted certificates, to a ZIP archive.
 *
 * The archive always bundles the configuration text and any trusted-certificate material for the
 * exported scope(s); `--format` selects the configuration format *inside* the archive.
 *
 * Usage examples:
 * ```
 * omnisign config export global.zip
 * omnisign config export global.zip --format yaml
 * omnisign config export full.zip --all
 * ```
 */
class ConfigExport : CliktCommand(name = "export"), KoinComponent {
	private val archive: ConfigArchiveUseCase by inject()

	private val outputFile by argument(
		help = "Destination archive path (a ZIP, conventionally .zip)."
	)

	private val format by option(
		"--format", "-f",
		help = "Configuration format inside the archive (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Inferred from the output file extension when recognized, otherwise JSON."
	).enum<ConfigFormat>()

	private val all by option(
		"--all", "-a",
		help = "Export the full application configuration instead of only the global section."
	).flag()

	override fun help(context: Context): String =
		"Export the global (or full) configuration and its trusted certificates to a ZIP archive"

	override fun run(): Unit = runBlocking {
		val resolvedFormat = format
			?: ConfigFormat.fromExtension(outputFile.substringAfterLast('.', ""))
			?: ConfigFormat.JSON
		val result = if (all) archive.exportApp(resolvedFormat) else archive.exportGlobal(resolvedFormat)

		result.fold(
			ifLeft = { error ->
				echo("❌ Export failed: ${error.message}", err = true)
				if (error.details != null) echo("Details: ${error.details}", err = true)
				throw ProgramResult(1)
			},
			ifRight = { bytes ->
				Path.of(outputFile).writeBytes(bytes)
				val scope = if (all) "full application" else "global"
				echo("✅ $scope configuration exported to $outputFile (${resolvedFormat.name} archive)")
			}
		)
	}
}
