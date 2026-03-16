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
		help = "Show detailed validation output including certificate key usages, timestamp IDs, and resolved configuration"
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
		val operationConfig = configOverrides.toOperationConfig()
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
			inputFile = file.toAbsolutePath().toString(),
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
	 * Print the full validation report.
	 *
	 * Normal mode already shows all cryptographically meaningful data: indication,
	 * qualification, algorithms, certificate identity, and TSA identity.
	 * When [detailed] is true, additional low-level fields are appended: the raw DSS
	 * signature/timestamp IDs, certificate key usages, public key algorithm, SHA-256
	 * fingerprint, timestamp info messages, and the resolved configuration block.
	 */
	private fun printValidationReport(
		report: ValidationReport,
		parameters: ValidationParameters,
		resolvedConfig: ResolvedConfig?,
	) {
		echo("═══════════════════════════════════════════════════════════════")
		echo("                    VALIDATION REPORT")
		echo("═══════════════════════════════════════════════════════════════")
		echo("Document:      ${report.documentName}")
		echo("Validated at:  ${report.validationTime}")
		echo("Overall:       ${formatOverallResult(report.overallResult)}")
		
		if (detailed) {
			echo("───────────────────────────────────────────────────────────────")
			val policyType = resolvedConfig?.validation?.policyType?.name ?: "DEFAULT_ETSI"
			val effectivePolicyPath = parameters.customPolicyPath
				?: resolvedConfig?.validation?.customPolicyPath
			echo("Policy:        $policyType${effectivePolicyPath?.let { " ($it)" } ?: ""}")
			echo("Revocation:    ${if (resolvedConfig?.validation?.checkRevocation != false) "Enabled" else "Disabled"}")
			echo("EU LOTL:       ${if (resolvedConfig?.validation?.useEuLotl != false) "Enabled" else "Disabled"}")
			val trustedLists = resolvedConfig?.validation?.customTrustedLists.orEmpty()
			if (trustedLists.isNotEmpty()) {
				echo("Trusted lists:")
				trustedLists.forEach { echo("  • ${it.name} (${it.source})") }
			}
		}
		
		echo("═══════════════════════════════════════════════════════════════")
		
		if (report.tlWarnings.isNotEmpty()) {
			echo("")
			report.tlWarnings.forEach { echo("⚠️ $it") }
		}
		
		if (report.signatures.isEmpty()) {
			echo("\n⚠️ No signatures found in the document.")
			return
		}
		
		report.signatures.forEachIndexed { index, signature ->
			printSignature(index, report.signatures.size, signature)
		}
		
		if (report.timestamps.isNotEmpty()) {
			printTimestamps(report.timestamps, report.overallResult)
		}
		
		echo("\n═══════════════════════════════════════════════════════════════")
		echo(formatSummary(report))
		echo("═══════════════════════════════════════════════════════════════")
	}
	
	/**
	 * Print a single signature block.
	 *
	 * Normal mode shows all cryptographically relevant facts: indication, signer,
	 * level, the best signature time, qualification, hash and encryption algorithms,
	 * and full certificate identity fields.
	 *
	 * In [detailed] mode additional fields are included: the raw DSS signature ID,
	 * certificate key usages, public key algorithm, and the SHA-256 fingerprint.
	 */
	private fun printSignature(index: Int, total: Int, signature: SignatureValidationResult) {
		echo("\n┌─ Signature ${index + 1} of $total")
		echo("│")
		if (detailed) {
			echo("│  ID:               ${signature.signatureId}")
		}
		echo("│  Indication:       ${formatIndication(signature.indication)}")
		if (signature.subIndication != null) {
			echo("│  Sub-indication:   ${signature.subIndication}")
		}
		echo("│  Signed by:        ${signature.signedBy}")
		echo("│  Signature level:  ${signature.signatureLevel}")
		echo("│  Signature time:   ${signature.signatureTime}")
		if (signature.signatureQualification != null) {
			echo("│  Qualification:    ${signature.signatureQualification}")
		}
		if (signature.hashAlgorithm != null || signature.encryptionAlgorithm != null) {
			val algStr = listOfNotNull(signature.hashAlgorithm, signature.encryptionAlgorithm).joinToString(" / ")
			echo("│  Algorithms:       $algStr")
		}
		echo("│")
		echo("│  Certificate:")
		echo("│    Subject:        ${signature.certificate.subjectDN}")
		echo("│    Issuer:         ${signature.certificate.issuerDN}")
		echo("│    Serial:         ${signature.certificate.serialNumber}")
		echo("│    Valid from:     ${signature.certificate.validFrom}")
		echo("│    Valid to:       ${signature.certificate.validTo}")
		echo("│    Qualified:      ${if (signature.certificate.isQualified) "Yes" else "No"}")
		
		if (detailed) {
			if (signature.certificate.publicKeyAlgorithm != null) {
				echo("│    Public key:     ${signature.certificate.publicKeyAlgorithm}")
			}
			if (signature.certificate.keyUsages.isNotEmpty()) {
				echo("│    Key usages:     ${signature.certificate.keyUsages.joinToString(", ")}")
			}
			if (signature.certificate.sha256Fingerprint != null) {
				echo("│    SHA-256:        ${signature.certificate.sha256Fingerprint}")
			}
		}
		
		if (signature.errors.isNotEmpty()) {
			echo("│")
			echo("│  ❌ Errors:")
			signature.errors.forEach { error ->
				echo("│     • $error")
			}
		}
		
		if (signature.warnings.isNotEmpty()) {
			echo("│")
			echo("│  ⚠️ Warnings:")
			signature.warnings.forEach { warning ->
				echo("│     • $warning")
			}
		}
		
		if (signature.infos.isNotEmpty()) {
			echo("│")
			echo("│  ℹ️ Information:")
			signature.infos.forEach { info ->
				echo("│     • $info")
			}
		}
		
		echo("└" + "─".repeat(63))
	}
	
	/**
	 * Print the timestamps block.
	 *
	 * Normal mode shows type, indication, sub-indication, production time, qualification,
	 * and the TSA subject DN. In [detailed] mode the raw DSS timestamp token ID and
	 * informational messages are also included.
	 *
	 * When any timestamp is [ValidationIndication.INDETERMINATE] within an otherwise
	 * [ValidationResult.VALID] signature, an informational note is prepended explaining
	 * that this is expected behaviour for PAdES-BASELINE-LTA and does not affect the
	 * overall validity.
	 */
	private fun printTimestamps(timestamps: List<TimestampValidationResult>, overallResult: ValidationResult) {
		echo("\n┌─ Timestamps (${timestamps.size})")

		val hasExpectedIndeterminate = overallResult == ValidationResult.VALID &&
				timestamps.any { it.indication == ValidationIndication.INDETERMINATE }
		if (hasExpectedIndeterminate) {
			echo("│")
			echo("│  ℹ️ Timestamps marked INDETERMINATE are a normal artefact of DSS's strict")
			echo("│     ETSI EN 319 102-1 standalone validation. Each timestamp is checked in")
			echo("│     isolation before being aggregated into the overall result, so the TSA")
			echo("│     certificate revocation cannot always be proven at the exact timestamp")
			echo("│     production time. PDF readers (e.g. Adobe) report both timestamps as")
			echo("│     valid because they use a simpler PKIX chain check. The ✅ PASSED above")
			echo("│     is the authoritative result. Renew the archive timestamp periodically")
			echo("│     (digital continuity) to keep the chain cryptographically provable.")
		}
		timestamps.forEachIndexed { index, timestamp ->
			echo("│")
			echo("│  ${index + 1}. ${timestamp.type}")
			if (detailed) {
				echo("│     ID:            ${timestamp.timestampId}")
			}
			echo("│     Indication:    ${formatTimestampIndication(timestamp.indication, overallResult)}")
			if (timestamp.subIndication != null) {
				echo("│     Sub-indication: ${timestamp.subIndication}")
			}
			echo("│     Produced:      ${timestamp.productionTime}")
			if (timestamp.qualification != null) {
				echo("│     Qualification: ${timestamp.qualification}")
			}
			if (timestamp.tsaSubjectDN != null) {
				echo("│     TSA:           ${timestamp.tsaSubjectDN}")
			}
			if (timestamp.errors.isNotEmpty()) {
				echo("│     ❌ Errors:")
				timestamp.errors.forEach { echo("│        • $it") }
			}
			if (timestamp.warnings.isNotEmpty()) {
				echo("│     ⚠️ Warnings:")
				timestamp.warnings.forEach { echo("│        • $it") }
			}
			if (detailed && timestamp.infos.isNotEmpty()) {
				echo("│     ℹ️ Information:")
				timestamp.infos.forEach { echo("│        • $it") }
			}
		}
		echo("│")
		echo("└" + "─".repeat(63))
	}
	
	private fun formatOverallResult(result: ValidationResult): String = when (result) {
		ValidationResult.VALID -> "✅ VALID"
		ValidationResult.INVALID -> "❌ INVALID"
		ValidationResult.INDETERMINATE -> "⚠️ INDETERMINATE"
	}
	
	private fun formatIndication(indication: ValidationIndication): String = when (indication) {
		ValidationIndication.TOTAL_PASSED -> "✅ PASSED"
		ValidationIndication.TOTAL_FAILED -> "❌ FAILED"
		ValidationIndication.INDETERMINATE -> "⚠️ INDETERMINATE"
	}

	/**
	 * Format a timestamp indication, using ℹ️ instead of ⚠️ for [ValidationIndication.INDETERMINATE]
	 * when [overallResult] is [ValidationResult.VALID].
	 *
	 * In a valid LTA signature, INDETERMINATE timestamps are an expected artefact of DSS's strict
	 * standalone ETSI EN 319 102-1 validation — not a real problem — so the warning emoji would be
	 * misleading.
	 */
	private fun formatTimestampIndication(indication: ValidationIndication, overallResult: ValidationResult): String =
		if (indication == ValidationIndication.INDETERMINATE && overallResult == ValidationResult.VALID) {
			"ℹ️  INDETERMINATE"
		} else {
			formatIndication(indication)
		}
	
	private fun formatSummary(report: ValidationReport): String {
		val passed = report.signatures.count { it.indication == ValidationIndication.TOTAL_PASSED }
		val failed = report.signatures.count { it.indication == ValidationIndication.TOTAL_FAILED }
		val indeterminate = report.signatures.count { it.indication == ValidationIndication.INDETERMINATE }
		
		return buildString {
			append("Summary: ")
			append("$passed passed")
			if (failed > 0) append(", $failed failed")
			if (indeterminate > 0) append(", $indeterminate indeterminate")
			append(" (${report.signatures.size} total)")
		}
	}
}
