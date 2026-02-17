package cz.pizavo.omnisign.data.repository

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import eu.europa.esig.dss.enumerations.SignatureQualification
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.simplereport.SimpleReport
import eu.europa.esig.dss.validation.SignedDocumentValidator
import eu.europa.esig.dss.validation.reports.Reports
import java.io.File

/**
 * JVM implementation of ValidationRepository using DSS library and Arrow Either.
 */
class DssValidationRepository : ValidationRepository {
	
	override suspend fun validateDocument(parameters: ValidationParameters): OperationResult<ValidationReport> {
		return Either.catch {
			val file = File(parameters.inputFile)
			if (!file.exists()) {
				return ValidationError.InvalidDocument(
					message = "File not found: ${parameters.inputFile}",
					details = null
				).left()
			}
			
			// Create validator from document
			val document = FileDocument(file)
			val validator = SignedDocumentValidator.fromDocument(document)
			
			// TODO: Configure certificate source, validation policy, etc.
			
			// Perform validation
			val reports = validator.validateDocument()
			
			// Convert DSS reports to our domain model
			convertDssReports(reports, file.name)
		}.mapLeft { exception ->
			ValidationError.ValidationFailed(
				message = "Validation failed",
				details = exception.message,
				cause = exception
			)
		}
	}
	
	/**
	 * Convert DSS Reports to our ValidationReport model.
	 */
	private fun convertDssReports(reports: Reports, documentName: String): ValidationReport {
		val simpleReport = reports.simpleReport
		// TODO: Use detailedReport for extracting more certificate details
		// val detailedReport = reports.detailedReport
		// TODO: Use diagnosticData for advanced validation information
		// val diagnosticData = reports.diagnosticData
		
		val signatureIds = simpleReport.signatureIdList
		val signatures = signatureIds.map { signatureId ->
			convertSignatureValidation(simpleReport, signatureId)
		}
		
		// Determine overall result
		val overallResult = when {
			signatures.all { it.indication == ValidationIndication.TOTAL_PASSED } -> ValidationResult.VALID
			signatures.any { it.indication == ValidationIndication.TOTAL_FAILED } -> ValidationResult.INVALID
			else -> ValidationResult.INDETERMINATE
		}
		
		return ValidationReport(
			documentName = documentName,
			validationTime = java.time.Instant.now().toString(),
			overallResult = overallResult,
			signatures = signatures,
			timestamps = emptyList() // TODO: Extract timestamp info
		)
	}
	
	/**
	 * Convert a single signature validation result.
	 */
	private fun convertSignatureValidation(
		simpleReport: SimpleReport,
		signatureId: String
	): SignatureValidationResult {
		val indication = when (simpleReport.getIndication(signatureId)) {
			eu.europa.esig.dss.enumerations.Indication.TOTAL_PASSED -> ValidationIndication.TOTAL_PASSED
			eu.europa.esig.dss.enumerations.Indication.TOTAL_FAILED -> ValidationIndication.TOTAL_FAILED
			else -> ValidationIndication.INDETERMINATE
		}
		
		val subIndication = simpleReport.getSubIndication(signatureId)?.toString()
		
		// DSS 6.x uses separate methods for AdES validation and qualification messages
		val adesErrors = simpleReport.getAdESValidationErrors(signatureId)
		val adesWarnings = simpleReport.getAdESValidationWarnings(signatureId)
		val adesInfos = simpleReport.getAdESValidationInfo(signatureId)
		
		val qualificationErrors = simpleReport.getQualificationErrors(signatureId)
		val qualificationWarnings = simpleReport.getQualificationWarnings(signatureId)
		val qualificationInfos = simpleReport.getQualificationInfo(signatureId)
		
		// Combine all messages
		val errors = (adesErrors + qualificationErrors).map { it.value }
		val warnings = (adesWarnings + qualificationWarnings).map { it.value }
		val infos = (adesInfos + qualificationInfos).map { it.value }
		
		val signedBy = simpleReport.getSignedBy(signatureId) ?: "Unknown"
		val signatureLevel = simpleReport.getSignatureFormat(signatureId)?.toString() ?: "Unknown"
		val signatureTime = simpleReport.getBestSignatureTime(signatureId)?.toString() ?: "Unknown"
		
		// Extract certificate info
		val certificate = CertificateInfo(
			subjectDN = signedBy,
			issuerDN = "Unknown", // TODO: Extract from detailed report
			serialNumber = "Unknown", // TODO: Extract from detailed report
			validFrom = "Unknown",
			validTo = "Unknown",
			keyUsages = emptyList(),
			isQualified = simpleReport.getSignatureQualification(signatureId)
				?.let { it != SignatureQualification.NA } ?: false
		)
		
		return SignatureValidationResult(
			signatureId = signatureId,
			indication = indication,
			subIndication = subIndication,
			errors = errors,
			warnings = warnings,
			infos = infos,
			signedBy = signedBy,
			signatureLevel = signatureLevel,
			signatureTime = signatureTime,
			certificate = certificate
		)
	}
}





