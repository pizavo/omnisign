package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.usecase.ConfigArchiveUseCase
import cz.pizavo.omnisign.domain.usecase.ExportImportConfigUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * CLI subcommand that imports a profile and upserts it into the configuration.
 *
 * A ZIP archive produced by `config profile export` is detected automatically and its bundled
 * trusted certificates are restored into the imported profile's trust scope; a plain configuration
 * file (the legacy text format) is still accepted, with its format inferred from the extension or
 * `--format`.
 *
 * Usage examples:
 * ```
 * omnisign config profile import my-profile.zip
 * omnisign config profile import my-profile.zip --name renamed-profile
 * omnisign config profile import legacy-profile.yaml
 * ```
 */
class ProfileImport : CliktCommand(name = "import"), KoinComponent {
	private val archive: ConfigArchiveUseCase by inject()
	private val exportImport: ExportImportConfigUseCase by inject()

	private val inputFile by argument(
		help = "Source file path: a ZIP archive, or a plain profile file in the legacy text format."
	)

	private val format by option(
		"--format", "-f",
		help = "Format of a legacy plain profile file (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Ignored for ZIP archives; inferred from the input file extension when omitted."
	).enum<ConfigFormat>()

	private val name by option(
		"--name", "-n",
		help = "Override the profile name from the file. If omitted, the name embedded in the file is used."
	)

	override fun help(context: Context): String =
		"Import a profile from a ZIP archive or legacy text file"

	override fun run(): Unit = runBlocking {
		val bytes = runCatching { Path.of(inputFile).readBytes() }.getOrElse { e ->
			echo("❌ Cannot read file '$inputFile': ${e.message}", err = true)
			return@runBlocking
		}

		val result = if (ConfigArchiveUseCase.isArchive(bytes)) {
			archive.importProfile(bytes, name)
		} else {
			val resolvedFormat = resolveFormat(inputFile, format) ?: return@runBlocking
			exportImport.importProfile(bytes.decodeToString(), resolvedFormat, name)
		}

		result.fold(
			ifLeft = { error ->
				echo("❌ Import failed: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
			},
			ifRight = { savedName ->
				echo("✅ Profile '$savedName' imported from $inputFile")
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
