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
import cz.pizavo.omnisign.domain.usecase.ExportImportConfigUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * CLI subcommand that imports a global or full application configuration.
 *
 * A ZIP archive produced by `config export` is detected automatically and its bundled trusted
 * certificates are restored into the trust store; a plain configuration file (the legacy text
 * format) is still accepted, with its format inferred from the extension or `--format`.
 *
 * Usage examples:
 * ```
 * omnisign config import global.zip
 * omnisign config import full.zip --all
 * omnisign config import legacy-global.yaml
 * ```
 */
class ConfigImport : CliktCommand(name = "import"), KoinComponent {
	private val archive: ConfigArchiveUseCase by inject()
	private val exportImport: ExportImportConfigUseCase by inject()

	private val inputFile by argument(
		help = "Source file path: a ZIP archive, or a plain configuration file in the legacy text format."
	)

	private val format by option(
		"--format", "-f",
		help = "Format of a legacy plain configuration file (${ConfigFormat.entries.joinToString { it.name }}). " +
				"Ignored for ZIP archives; inferred from the input file extension when omitted."
	).enum<ConfigFormat>()

	private val all by option(
		"--all", "-a",
		help = "Import as a full application configuration, replacing all sections."
	).flag()

	override fun help(context: Context): String =
		"Import a global (or full) configuration from a ZIP archive or legacy text file"

	override fun run(): Unit = runBlocking {
		val bytes = runCatching { Path.of(inputFile).readBytes() }.getOrElse { e ->
			echo("❌ Cannot read file '$inputFile': ${e.message}", err = true)
			throw ProgramResult(1)
		}

		val result = if (ConfigArchiveUseCase.isArchive(bytes)) {
			if (all) archive.importApp(bytes) else archive.importGlobal(bytes)
		} else {
			val resolvedFormat = resolveFormat(inputFile, format) ?: throw ProgramResult(1)
			val text = bytes.decodeToString()
			if (all) exportImport.importApp(text, resolvedFormat) else exportImport.importGlobal(text, resolvedFormat)
		}

		result.fold(
			ifLeft = { error ->
				echo("❌ Import failed: ${error.message}", err = true)
				if (error.details != null) echo("Details: ${error.details}", err = true)
				error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
				throw ProgramResult(1)
			},
			ifRight = {
				val scope = if (all) "full application" else "global"
				echo("✅ $scope configuration imported from $inputFile")
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
