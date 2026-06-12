package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.cli.OperationConfigOptions
import cz.pizavo.omnisign.cli.OutputConfig
import cz.pizavo.omnisign.cli.json.JsonError
import cz.pizavo.omnisign.cli.json.JsonValidationResult
import cz.pizavo.omnisign.cli.json.toJsonError
import cz.pizavo.omnisign.cli.json.toJsonResult
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI command for validating signed PDF documents.
 */
class Validate : CliktCommand(
	name = "validate",
), KoinComponent {
	private val validateUseCase: ValidateDocumentUseCase by inject()
	private val configRepository: ConfigRepository by inject()
	private val passwordCallback: PasswordCallback by inject()
	private val output by requireObject<OutputConfig>()

	private val file by option("-f", "--file", help = "Path to the PDF file to validate")
		.path(
			mustExist = true,
			canBeDir = false,
			mustBeReadable = true,
		).required()

	private val policy by option("-p", "--policy", help = "Path to custom validation policy file")
		.path(mustExist = true, canBeDir = false, mustBeReadable = true)

	private val profile by option(
		"--profile",
		help = "Use a named configuration profile for this operation"
	)

	private val detailed by option(
		"-d",
		"--detailed",
		help = "Show detailed validation output: every certificate's full parsed dump, the raw DSS signature/timestamp IDs, and the resolved configuration"
	).flag(default = false)

	private val reportOut by option(
		"--report-out",
		help = "Write the raw DSS report to this file path (XML format chosen by --report-format)"
	).path(canBeDir = false)

	private val reportFormat by option(
		"--report-format",
		help = "Format of the raw report written by --report-out (${RawReportFormat.entries.joinToString { it.name }}). Default: XML_DETAILED"
	).enum<RawReportFormat>().default(RawReportFormat.XML_DETAILED)

	private val configOverrides by OperationConfigOptions()

	override fun help(context: Context): String =
		"Validate a signed PDF document"

	override fun run(): Unit = runBlocking {
		val appConfig = configRepository.getCurrentConfig()
		val activeProfile = profile
			?: appConfig.activeProfile
		val profileConfig = activeProfile?.let { appConfig.profiles[it] }
		val operationConfig = configOverrides.toOperationConfig(passwordCallback)
		val resolvedConfigResult = ResolvedConfig.resolve(
			global = appConfig.global,
			profile = profileConfig,
			operationOverrides = operationConfig,
			excludeGlobalTls = configOverrides.noGlobalTls
		)
		if (resolvedConfigResult.isLeft()) {
			val error = resolvedConfigResult.leftOrNull()!!
			if (output.json) {
				echo(Json.encodeToString(JsonValidationResult(
					success = false,
					error = JsonError(message = "Configuration Error: ${error.message}")
				)))
			} else {
				echo("❌ Configuration Error: ${error.message}", err = true)
			}
			throw ProgramResult(1)
		}
		val resolvedConfig = resolvedConfigResult.getOrNull()!!

		val parameters = ValidationParameters(
			inputBytes = file.toFile().readBytes(),
			inputName = file.fileName.toString(),
			customPolicyPath = policy?.toAbsolutePath()?.toString(),
			resolvedConfig = resolvedConfig,
			rawReportOutputPath = reportOut?.toAbsolutePath()?.toString(),
			rawReportFormat = reportFormat,
		)

		validateUseCase(parameters).fold(
			ifLeft = { error ->
				if (output.json) {
					echo(Json.encodeToString(JsonValidationResult(
						success = false,
						error = error.toJsonError()
					)))
				} else {
					echo("❌ Validation Error: ${error.message}", err = true)
					error.details?.let { echo("Details: $it", err = true) }
					error.cause?.let { echo("Cause: ${it.message}", err = true) }
				}
				throw ProgramResult(1)
			},
			ifRight = { report ->
				val rawPath = reportOut?.toAbsolutePath()?.toString()
				if (output.json) {
					echo(Json.encodeToString(report.toJsonResult(rawReportPath = rawPath)))
				} else {
					printValidationReport(report, parameters, resolvedConfig)
					reportOut?.let {
						echo("\n📄 Raw report (${reportFormat.name}) written to: ${it.toAbsolutePath()}")
					}
				}
			}
		)
	}

	/**
	 * Print the validation report as plain text via [toPlainText] — the same rendering the desktop
	 * `.txt` export uses, so the terminal and the export never drift and both carry the full
	 * certificate chain and revocation evidence. [detailed] is forwarded to expand every certificate
	 * into its full parsed dump.
	 *
	 * Two CLI-only additions wrap that shared body: in [detailed] mode the resolved configuration that
	 * was actually applied, and — for a [ValidationResult.VALID] signature whose timestamps report
	 * [ValidationIndication.INDETERMINATE] — a note explaining why that is expected and not a problem.
	 */
	private fun printValidationReport(
		report: ValidationReport,
		parameters: ValidationParameters,
		resolvedConfig: ResolvedConfig?,
	) {
		echo(report.toPlainText(detailed = detailed))

		if (detailed) {
			echo("── Configuration ──")
			val policyType = resolvedConfig?.validation?.policyType?.name ?: "DEFAULT_ETSI"
			val effectivePolicyPath = parameters.customPolicyPath ?: resolvedConfig?.validation?.customPolicyPath
			echo("Policy: $policyType${effectivePolicyPath?.let { " ($it)" } ?: ""}")
			echo("Revocation: ${if (resolvedConfig?.validation?.checkRevocation != false) "Enabled" else "Disabled"}")
			echo("EU LOTL: ${if (resolvedConfig?.validation?.useEuLotl != false) "Enabled" else "Disabled"}")
			val trustedLists = resolvedConfig?.validation?.customTrustedLists.orEmpty()
			if (trustedLists.isNotEmpty()) {
				echo("Trusted lists:")
				trustedLists.forEach { echo("• ${it.name} (${it.source})") }
			}
		}

		val expectedIndeterminate = report.overallResult == ValidationResult.VALID &&
			report.timestamps.any { it.indication == ValidationIndication.INDETERMINATE }
		if (expectedIndeterminate) {
			echo("")
			echo("Note: timestamps shown INDETERMINATE are a normal artefact of DSS's strict ETSI EN")
			echo("319 102-1 standalone validation — each timestamp is verified in isolation, so the TSA")
			echo("certificate's revocation cannot always be proven at its exact production time. The")
			echo("overall result above is authoritative; renew the archive timestamp periodically to keep")
			echo("the chain cryptographically provable.")
		}
	}
}
