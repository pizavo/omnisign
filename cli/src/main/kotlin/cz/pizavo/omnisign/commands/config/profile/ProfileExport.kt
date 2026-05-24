package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
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
 * CLI subcommand that exports a single named profile, together with its directly trusted
 * certificates, to a ZIP archive.
 *
 * `--format` selects the configuration format *inside* the archive.
 *
 * Usage examples:
 * ```
 * omnisign config profile export my-profile my-profile.zip
 * omnisign config profile export my-profile my-profile.zip --format yaml
 * ```
 */
class ProfileExport : CliktCommand(name = "export"), KoinComponent {
	private val archive: ConfigArchiveUseCase by inject()

	private val profileName by argument(help = "Name of the profile to export")

	private val outputFile by argument(
		help = "Destination archive path (a ZIP, conventionally .zip)."
	)

	private val format by option(
		"--format", "-f",
		help = "Configuration format inside the archive (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Inferred from the output file extension when recognized, otherwise JSON."
	).enum<ConfigFormat>()

	override fun help(context: Context): String =
		"Export a named profile and its trusted certificates to a ZIP archive"

	override fun run(): Unit = runBlocking {
		val resolvedFormat = format
			?: ConfigFormat.fromExtension(outputFile.substringAfterLast('.', ""))
			?: ConfigFormat.JSON

		archive.exportProfile(profileName, resolvedFormat).fold(
			ifLeft = { error ->
				echo("❌ Export failed: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
			},
			ifRight = { bytes ->
				Path.of(outputFile).writeBytes(bytes)
				echo("✅ Profile '$profileName' exported to $outputFile (${resolvedFormat.name} archive)")
			}
		)
	}
}
