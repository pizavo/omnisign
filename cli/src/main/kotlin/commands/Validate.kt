package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.cli.OperationConfigOptions
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import kotlinx.coroutines.runBlocking
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
	
	private val configOverrides by OperationConfigOptions()
	
	override fun help(context: Context): String =
		"Validate a signed PDF document"
	
	override fun run(): Unit = runBlocking {
		val appConfig = configRepository.getCurrentConfig()
		val activeProfile = profile
			?: appConfig.activeProfile
		val profileConfig = activeProfile?.let { appConfig.profiles[it] }
		val operationConfig = configOverrides.toOperationConfig()
		val resolvedConfig = ResolvedConfig.resolve(
			global = appConfig.global,
			profile = profileConfig,
			operationOverrides = operationConfig,
			excludeGlobalTls = configOverrides.noGlobalTls
		)
		
		val parameters = ValidationParameters(
			inputFile = file.toAbsolutePath().toString(),
			customPolicyPath = policy?.toAbsolutePath()?.toString(),
			resolvedConfig = resolvedConfig
		)
		
		validateUseCase(parameters).fold(
			ifLeft = { error ->
				echo("❌ Validation Error: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.let { echo("Cause: ${it.message}", err = true) }
			},
			ifRight = { report ->
				printValidationReport(report, parameters, resolvedConfig)
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
		
		if (report.signatures.isEmpty()) {
			echo("\n⚠️ No signatures found in the document.")
			return
		}
		
		report.signatures.forEachIndexed { index, signature ->
			printSignature(index, report.signatures.size, signature)
		}
		
		if (report.timestamps.isNotEmpty()) {
			printTimestamps(report.timestamps)
		}
		
		echo("\n═══════════════════════════════════════════════════════════════")
		echo(formatSummary(report))
		echo("═══════════════════════════════════════════════════════════════")
	}
	
	/**
	 * Print a single signature block.
	 *
	 * Normal mode shows all cryptographically relevant facts: indication, signer,
	 * level, best signature time, qualification, hash and encryption algorithms,
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
	 */
	private fun printTimestamps(timestamps: List<TimestampValidationResult>) {
		echo("\n┌─ Timestamps (${timestamps.size})")
		timestamps.forEachIndexed { index, timestamp ->
			echo("│")
			echo("│  ${index + 1}. ${timestamp.type}")
			if (detailed) {
				echo("│     ID:            ${timestamp.timestampId}")
			}
			echo("│     Indication:    ${formatIndication(timestamp.indication)}")
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