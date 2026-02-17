package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
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
	
	private val file by option("-f", "--file", help = "Path to the PDF file to validate")
		.path(
			mustExist = true,
			canBeDir = false,
			mustBeReadable = true,
		).required()
	
	private val policy by option("-p", "--policy", help = "Path to custom validation policy file")
		.path(mustExist = true, canBeDir = false, mustBeReadable = true)
	
	override fun help(context: Context): String =
		"Validate a signed PDF document"
	
	override fun run(): Unit = runBlocking {
		val parameters = ValidationParameters(
			inputFile = file.toAbsolutePath().toString(),
			customPolicyPath = policy?.toAbsolutePath()?.toString()
		)
		
		validateUseCase(parameters).fold(
			ifLeft = { error ->
				echo("❌ Validation Error: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.let { echo("Cause: ${it.message}", err = true) }
			},
			ifRight = { report ->
				printValidationReport(report)
			}
		)
	}
	
	private fun printValidationReport(report: ValidationReport) {
		echo("═══════════════════════════════════════════════════════════════")
		echo("                    VALIDATION REPORT")
		echo("═══════════════════════════════════════════════════════════════")
		echo("Document:      ${report.documentName}")
		echo("Validated at:  ${report.validationTime}")
		echo("Overall:       ${formatOverallResult(report.overallResult)}")
		echo("═══════════════════════════════════════════════════════════════")
		
		if (report.signatures.isEmpty()) {
			echo("\n⚠️  No signatures found in the document.")
			return
		}
		
		report.signatures.forEachIndexed { index, signature ->
			echo("\n┌─ Signature ${index + 1} of ${report.signatures.size}")
			echo("│")
			echo("│  ID:               ${signature.signatureId}")
			echo("│  Indication:       ${formatIndication(signature.indication)}")
			if (signature.subIndication != null) {
				echo("│  Sub-indication:   ${signature.subIndication}")
			}
			echo("│  Signed by:        ${signature.signedBy}")
			echo("│  Signature level:  ${signature.signatureLevel}")
			echo("│  Signature time:   ${signature.signatureTime}")
			echo("│")
			echo("│  Certificate:")
			echo("│    Subject:        ${signature.certificate.subjectDN}")
			echo("│    Issuer:         ${signature.certificate.issuerDN}")
			echo("│    Serial:         ${signature.certificate.serialNumber}")
			echo("│    Valid from:     ${signature.certificate.validFrom}")
			echo("│    Valid to:       ${signature.certificate.validTo}")
			echo("│    Qualified:      ${if (signature.certificate.isQualified) "Yes" else "No"}")
			
			if (signature.errors.isNotEmpty()) {
				echo("│")
				echo("│  ❌ Errors:")
				signature.errors.forEach { error ->
					echo("│     • $error")
				}
			}
			
			if (signature.warnings.isNotEmpty()) {
				echo("│")
				echo("│  ⚠️  Warnings:")
				signature.warnings.forEach { warning ->
					echo("│     • $warning")
				}
			}
			
			if (signature.infos.isNotEmpty()) {
				echo("│")
				echo("│  ℹ️  Information:")
				signature.infos.forEach { info ->
					echo("│     • $info")
				}
			}
			
			echo("└" + "─".repeat(63))
		}
		
		if (report.timestamps.isNotEmpty()) {
			echo("\n┌─ Timestamps (${report.timestamps.size})")
			report.timestamps.forEachIndexed { index, timestamp ->
				echo("│  ${index + 1}. ${timestamp.timestampId} - ${timestamp.productionTime}")
			}
			echo("└" + "─".repeat(63))
		}
		
		echo("\n═══════════════════════════════════════════════════════════════")
		echo(formatSummary(report))
		echo("═══════════════════════════════════════════════════════════════")
	}
	
	private fun formatOverallResult(result: ValidationResult): String = when (result) {
		ValidationResult.VALID -> "✅ VALID"
		ValidationResult.INVALID -> "❌ INVALID"
		ValidationResult.INDETERMINATE -> "⚠️  INDETERMINATE"
	}
	
	private fun formatIndication(indication: ValidationIndication): String = when (indication) {
		ValidationIndication.TOTAL_PASSED -> "✅ PASSED"
		ValidationIndication.TOTAL_FAILED -> "❌ FAILED"
		ValidationIndication.INDETERMINATE -> "⚠️  INDETERMINATE"
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