package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.cli.OperationConfigOptions
import cz.pizavo.omnisign.cli.OutputConfig
import cz.pizavo.omnisign.cli.json.JsonError
import cz.pizavo.omnisign.cli.json.JsonExtensionResult
import cz.pizavo.omnisign.cli.json.toJsonError
import cz.pizavo.omnisign.cli.json.toJsonResult
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI command for extending a signed PDF to a higher PAdES level.
 *
 * Wraps [ExtendDocumentUseCase], which delegates to
 * [cz.pizavo.omnisign.domain.repository.ArchivingRepository.extendDocument].
 * Supported promotion paths:
 * - B-B → B-T (add RFC 3161 document timestamp)
 * - B-T → B-LT (embed CRL/OCSP revocation data)
 * - B-LT → B-LTA (add archival document timestamp)
 * - B-LTA → B-LTA (archival renewal — re-timestamp before expiry)
 *
 * A timestamp server (TSA) must be configured or supplied via `--timestamp-url`
 * for any target level of B-T or above.
 */
class Timestamp : CliktCommand(name = "timestamp"), KoinComponent {
	
	private val extendUseCase: ExtendDocumentUseCase by inject()
	private val configRepository: ConfigRepository by inject()
	private val output by requireObject<OutputConfig>()
	
	private val inputFile by option("-f", "--file", help = "Path to the signed PDF file to extend")
		.path(mustExist = true, canBeDir = false, mustBeReadable = true)
		.required()
	
	private val outputFile by option("-o", "--output", help = "Path for the extended output PDF file")
		.path(canBeDir = false)
		.required()
	
	private val targetLevel by option(
		"-l", "--level",
		help = "Target PAdES level to extend to (${extendableLevels().joinToString { it.name }}). Default: PADES_BASELINE_T"
	).enum<SignatureLevel>().default(SignatureLevel.PADES_BASELINE_T)
	
	private val profile by option(
		"--profile",
		help = "Use a named configuration profile for this operation"
	)
	
	private val configOverrides by OperationConfigOptions()
	
	override fun help(context: Context): String =
		"Extend a signed PDF to a higher PAdES level (add timestamp, revocation data, or archival timestamp)"
	
	override fun run(): Unit = runBlocking {
		val appConfig = configRepository.getCurrentConfig()
		val activeProfile = profile ?: appConfig.activeProfile
		val profileConfig = activeProfile?.let { appConfig.profiles[it] }
		val operationConfig = configOverrides.toOperationConfig()
		val resolvedConfigResult = ResolvedConfig.resolve(
			global = appConfig.global,
			profile = profileConfig,
			operationOverrides = operationConfig
		)
		if (resolvedConfigResult.isLeft()) {
			val error = resolvedConfigResult.leftOrNull()!!
			if (output.json) {
				echo(
					Json.encodeToString(
						JsonExtensionResult(
							success = false,
							error = JsonError(message = "Configuration Error: ${error.message}")
						)
					)
				)
			} else {
				echo("❌ Configuration Error: ${error.message}", err = true)
			}
			throw ProgramResult(1)
		}
		val resolvedConfig = resolvedConfigResult.getOrNull()!!
		
		val parameters = ArchivingParameters(
			inputFile = inputFile.toAbsolutePath().toString(),
			outputFile = outputFile.toAbsolutePath().toString(),
			targetLevel = targetLevel,
			resolvedConfig = resolvedConfig
		)
		
		extendUseCase(parameters).fold(
			ifLeft = { error ->
				if (output.json) {
					echo(
						Json.encodeToString(
							JsonExtensionResult(
								success = false,
								error = error.toJsonError()
							)
						)
					)
				} else {
					echo("❌ Extension Error: ${error.message}", err = true)
					error.details?.let { echo("Details: $it", err = true) }
					error.cause?.let { echo("Cause: ${it.message}", err = true) }
				}
				throw ProgramResult(1)
			},
			ifRight = { result ->
				if (output.json) {
					echo(Json.encodeToString(result.toJsonResult()))
				} else {
					result.warnings.forEach { warning ->
						echo("⚠️ Warning: $warning", err = true)
					}
					if (output.verbose && result.rawWarnings.isNotEmpty()) {
						echo("  Raw DSS warnings:", err = true)
						result.rawWarnings.forEach { raw ->
							echo("    • $raw", err = true)
						}
					}
					printExtensionResult(result.outputFile, result.newSignatureLevel)
				}
			}
		)
	}
	
	/**
	 * Print a formatted summary of the completed extension operation to stdout.
	 */
	private fun printExtensionResult(outputFile: String, newLevel: String) {
		if (output.quiet) return
		echo("═══════════════════════════════════════════════════════════════")
		echo("                     TIMESTAMP RESULT")
		echo("═══════════════════════════════════════════════════════════════")
		echo("✅ Document extended successfully")
		echo("")
		echo("Output file    : $outputFile")
		echo("New level      : $newLevel")
		echo("═══════════════════════════════════════════════════════════════")
	}
}

/**
 * Returns the PAdES levels that are valid extension targets (B-T and above).
 */
private fun extendableLevels() = SignatureLevel.entries.filter { it != SignatureLevel.PADES_BASELINE_B }
