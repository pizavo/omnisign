package cz.pizavo.omnisign.data.repository

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.ades.policy.AdESPolicy
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import eu.europa.esig.dss.detailedreport.DetailedReport
import eu.europa.esig.dss.diagnostic.DiagnosticData
import eu.europa.esig.dss.diagnostic.TimestampWrapper
import eu.europa.esig.dss.enumerations.Indication
import eu.europa.esig.dss.enumerations.SignatureQualification
import eu.europa.esig.dss.enumerations.SubIndication
import eu.europa.esig.dss.enumerations.TimestampQualification
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.simplereport.SimpleReport
import eu.europa.esig.dss.validation.SignedDocumentValidator
import eu.europa.esig.dss.validation.reports.Reports
import java.io.File

/**
 * JVM implementation of [ValidationRepository] using the EU DSS library.
 *
 * Builds a certificate verifier with online CRL/OCSP sources, AIA support,
 * and optional EU LOTL, or custom trusted lists — all driven by the [ResolvedConfig]
 * supplied in [ValidationParameters].
 */
class DssValidationRepository(
	private val dssServiceFactory: DssServiceFactory
) : ValidationRepository {
	
	private val adeSPolicy = AdESPolicy()
	
	override suspend fun validateDocument(parameters: ValidationParameters): OperationResult<ValidationReport> {
		return Either.catch {
			val file = File(parameters.inputFile)
			
			if (!file.exists()) {
				return ValidationError.InvalidDocument(
					message = "File not found: ${parameters.inputFile}",
					details = null
				).left()
			}
			
			val statusAlert = CollectingStatusAlert()
			val (cv, tlWarnings) = dssServiceFactory.buildValidationCertificateVerifier(
				parameters.resolvedConfig
			) { statusAlert }
			val validator = SignedDocumentValidator.fromDocument(FileDocument(file))
				.apply {
					setCertificateVerifier(cv)
					if (this is PDFDocumentValidator) {
						setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
					}
				}
			
			
			val reports = resolveValidationPolicy(parameters.resolvedConfig, parameters.customPolicyPath)
				?.let { validator.validateDocument(it) }
				?: validator.validateDocument()
			
			parameters.rawReportOutputPath?.let { outPath ->
				writeRawReport(reports, outPath, parameters.rawReportFormat)
			}
			
			val verifierWarnings = statusAlert.drain()
			val disabledHash = parameters.resolvedConfig?.disabledHashAlgorithms ?: emptySet()
			val disabledEncryption = parameters.resolvedConfig?.disabledEncryptionAlgorithms ?: emptySet()
			val report = convertReports(reports, file.name)
			val annotatedSignatures = report.signatures.map { sig ->
				annotateDisabledAlgorithms(sig, disabledHash, disabledEncryption)
			}
			report.copy(
				signatures = annotatedSignatures,
				tlWarnings = tlWarnings + verifierWarnings,
				rawReports = extractRawReports(reports),
			)
		}.mapLeft { exception ->
			ValidationError.ValidationFailed(
				message = "Validation failed",
				details = exception.message,
				cause = exception
			)
		}
	}
	
	/**
	 * Load a [eu.europa.esig.dss.model.policy.ValidationPolicy] from the resolved config
	 * or a custom policy path. Returns null to let DSS use its built-in default ETSI policy.
	 *
	 * Disabled hash / encryption algorithms from the [config] are forwarded to
	 * [AdESPolicy.load] so that DSS itself treats them as non-compliant.
	 */
	private fun resolveValidationPolicy(
		config: ResolvedConfig?,
		customPolicyPath: String?
	): eu.europa.esig.dss.model.policy.ValidationPolicy? {
		val policyFile = when {
			customPolicyPath != null -> File(customPolicyPath)
			config?.validation?.policyType == ValidationPolicyType.CUSTOM_FILE ->
				config.validation.customPolicyPath?.let { File(it) }
			
			else -> null
		}
		val constraints = config?.validation?.algorithmConstraints
		val disabledHash = config?.disabledHashAlgorithms ?: emptySet()
		val disabledEncryption = config?.disabledEncryptionAlgorithms ?: emptySet()
		return if (policyFile != null || constraints != null || disabledHash.isNotEmpty() || disabledEncryption.isNotEmpty()) {
			adeSPolicy.load(policyFile, constraints, disabledHash, disabledEncryption)
		} else {
			null
		}
	}
	
	/**
	 * Convert DSS [Reports] into our domain [ValidationReport].
	 */
	private fun convertReports(reports: Reports, documentName: String): ValidationReport {
		val simpleReport = reports.simpleReport
		val detailedReport = reports.detailedReport
		val diagnosticData = reports.diagnosticData
		
		val allTimestampResults = diagnosticData.getTimestampList().associate { tsw ->
			tsw.id to convertTimestamp(tsw, simpleReport, detailedReport)
		}
		
		val signatureTimestampIds = mutableSetOf<String>()
		
		val signatures = simpleReport.signatureIdList.map { sigId ->
			val sigWrapper = diagnosticData.getSignatureById(sigId)
			val sigTsIds = sigWrapper?.timestampList
				?.filter { it.type == eu.europa.esig.dss.enumerations.TimestampType.SIGNATURE_TIMESTAMP }
				?.map { it.id }
				?: emptyList()
			signatureTimestampIds.addAll(sigTsIds)
			
			val sigTimestamps = sigTsIds.mapNotNull { tsId -> allTimestampResults[tsId] }
			
			convertSignature(simpleReport, diagnosticData, sigId).copy(
				timestamps = sigTimestamps
			)
		}
		
		val documentTimestamps = allTimestampResults
			.filterKeys { it !in signatureTimestampIds }
			.values.toList()
		
		val overallResult = when {
			signatures.all { it.indication == ValidationIndication.TOTAL_PASSED } -> ValidationResult.VALID
			signatures.any { it.indication == ValidationIndication.TOTAL_FAILED } -> ValidationResult.INVALID
			else -> ValidationResult.INDETERMINATE
		}
		
		return ValidationReport(
			documentName = documentName,
			validationTime = kotlin.time.Clock.System.now(),
			overallResult = overallResult,
			signatures = signatures,
			timestamps = documentTimestamps
		)
	}
	
	/**
	 * Convert a single DSS [TimestampWrapper] into a [TimestampValidationResult].
	 *
	 * Indication is resolved with a cascade, so the most authoritative available source is used:
	 *
	 * 1. [SimpleReport.getIndication] — the context-aware, aggregated result for independent
	 *    (top-level) timestamps such as the `DOCUMENT_TIMESTAMP` in a PAdES-BASELINE-LTA document.
	 * 2. [SimpleReport.getSignatureTimestamps] lookup — for `SIGNATURE_TIMESTAMP` tokens that are
	 *    embedded inside a PAdES signature and not listed as top-level simple-report tokens.
	 * 3. [DetailedReport] archival-data / basic-timestamp APIs — defensive last resort.
	 *
	 * **DSS indication mapping:** DSS uses [Indication.PASSED] / [Indication.FAILED] for
	 * individual validation objects (timestamps, building blocks), while
	 * [Indication.TOTAL_PASSED] / [Indication.TOTAL_FAILED] are reserved for the overall
	 * signature validation result.  Both pairs are mapped to the corresponding domain
	 * [ValidationIndication] value.
	 *
	 * **Note on expected INDETERMINATE status:** In a valid PAdES-BASELINE-LTA signature it
	 * can happen that DSS reports one or both timestamps as `INDETERMINATE` — typically with
	 * sub-indication `NO_POE` — when the TSA certificate is not directly identified as a trust
	 * service in the loaded trusted lists.  This does not affect the overall `TOTAL_PASSED`
	 * result of the containing signature.  When the TSA certificate *is* a trust anchor
	 * (e.g., directly listed in the EU LOTL), DSS reports the timestamp as `PASSED`.
	 *
	 * The sub-indication is resolved from the simple-report first, falling back to the BBB
	 * conclusion (which commonly carries `NO_POE`) so callers have a human-readable reason code.
	 */
	private fun convertTimestamp(
		tsw: TimestampWrapper,
		simpleReport: SimpleReport,
		detailedReport: DetailedReport
	): TimestampValidationResult {
		val id = tsw.id
		
		var rawIndication: Indication? = simpleReport.getIndication(id)
		var rawSubIndication: SubIndication? = simpleReport.getSubIndication(id)
		
		if (rawIndication == null) {
			val sigId = tsw.timestampedSignatures.firstOrNull()?.id
			if (sigId != null) {
				val srTs = simpleReport.getSignatureTimestamps(sigId).find { it.id == id }
				rawIndication = srTs?.indication
				rawSubIndication = srTs?.subIndication
			}
		}
		
		if (rawIndication == null) {
			rawIndication = detailedReport.getArchiveDataTimestampValidationIndication(id)
				?: detailedReport.getBasicTimestampValidationIndication(id)
			rawSubIndication = rawSubIndication
				?: detailedReport.getArchiveDataTimestampValidationSubIndication(id)
						?: detailedReport.getBasicTimestampValidationSubIndication(id)
		}
		
		val indication = when (rawIndication) {
			Indication.TOTAL_PASSED, Indication.PASSED -> ValidationIndication.TOTAL_PASSED
			Indication.TOTAL_FAILED, Indication.FAILED -> ValidationIndication.TOTAL_FAILED
			else -> ValidationIndication.INDETERMINATE
		}
		
		val bbb = detailedReport.getBasicBuildingBlockById(id)
		
		if (rawSubIndication == null) {
			rawSubIndication = bbb?.conclusion?.subIndication
				?: detailedReport.getBasicBuildingBlocksSubIndication(id)
						?: detailedReport.getArchiveDataTimestampValidationSubIndication(id)
						?: detailedReport.getBasicTimestampValidationSubIndication(id)
		}
		
		val subIndication = rawSubIndication?.toString()
		
		val qualification = try {
			detailedReport.getTimestampQualification(id)
				?.takeIf { it != TimestampQualification.NA }
				?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
		} catch (_: Exception) {
			null
		}
		
		val errors = bbb?.conclusion?.errors?.map { it.value } ?: emptyList()
		val warnings = bbb?.conclusion?.warnings?.map { it.value } ?: emptyList()
		val infos = bbb?.conclusion?.infos?.map { it.value } ?: emptyList()
		
		val tsaSubjectDN = tsw.signingCertificate?.getCertificateDN()
		
		return TimestampValidationResult(
			timestampId = id,
			type = tsw.type?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unknown",
			indication = indication,
			subIndication = subIndication,
			productionTime = tsw.productionTime?.toKotlinInstant() ?: kotlin.time.Instant.fromEpochSeconds(0),
			qualification = qualification,
			tsaSubjectDN = tsaSubjectDN,
			errors = errors,
			warnings = warnings,
			infos = infos
		)
	}
	
	/**
	 * Convert a single DSS signature entry into a [SignatureValidationResult],
	 * pulling rich certificate details from the [DiagnosticData].
	 */
	private fun convertSignature(
		simpleReport: SimpleReport,
		diagnosticData: DiagnosticData,
		signatureId: String
	): SignatureValidationResult {
		val indication = when (simpleReport.getIndication(signatureId)) {
			Indication.TOTAL_PASSED, Indication.PASSED -> ValidationIndication.TOTAL_PASSED
			Indication.TOTAL_FAILED, Indication.FAILED -> ValidationIndication.TOTAL_FAILED
			else -> ValidationIndication.INDETERMINATE
		}
		
		val errors = simpleReport.getAdESValidationErrors(signatureId).map { it.value }
		val warnings = simpleReport.getAdESValidationWarnings(signatureId).map { it.value }
		val infos = simpleReport.getAdESValidationInfo(signatureId).map { it.value }
		val qualificationErrors = simpleReport.getQualificationErrors(signatureId).map { it.value }
		val qualificationWarnings = simpleReport.getQualificationWarnings(signatureId).map { it.value }
		val qualificationInfos = simpleReport.getQualificationInfo(signatureId).map { it.value }
		
		val signedBy = simpleReport.getSignedBy(signatureId) ?: "Unknown"
		val signatureLevel = simpleReport.getSignatureFormat(signatureId)?.toString() ?: "Unknown"
		val signatureTime = simpleReport.getBestSignatureTime(signatureId)?.toKotlinInstant()
			?: kotlin.time.Instant.fromEpochSeconds(0)
		
		val sigWrapper = diagnosticData.getSignatureById(signatureId)
		val signingCert = sigWrapper?.signingCertificate
		
		val sha256Fingerprint = signingCert?.digestAlgoAndValue?.digestValue?.let { bytes ->
			bytes.joinToString(":") { "%02X".format(it) }
		}
		
		val dssQualification = simpleReport.getSignatureQualification(signatureId)
		val trustTier = dssQualification?.toTrustTier() ?: SignatureTrustTier.NOT_QUALIFIED
		
		val certificate = CertificateInfo(
			subjectDN = signingCert?.getCertificateDN() ?: signedBy,
			issuerDN = signingCert?.getCertificateIssuerDN() ?: "Unknown",
			serialNumber = signingCert?.serialNumber ?: "Unknown",
			validFrom = signingCert?.notBefore?.toKotlinInstant() ?: kotlin.time.Instant.fromEpochSeconds(0),
			validTo = signingCert?.notAfter?.toKotlinInstant() ?: kotlin.time.Instant.fromEpochSeconds(0),
			keyUsages = signingCert?.keyUsages?.map { it.name } ?: emptyList(),
			isQualified = trustTier != SignatureTrustTier.NOT_QUALIFIED,
			publicKeyAlgorithm = signingCert?.encryptionAlgorithm?.name,
			sha256Fingerprint = sha256Fingerprint,
		)
		
		val signatureQualification = dssQualification
			?.takeIf { it != SignatureQualification.NA }
			?.readable
		
		return SignatureValidationResult(
			signatureId = signatureId,
			indication = indication,
			subIndication = simpleReport.getSubIndication(signatureId)?.toString(),
			errors = errors,
			warnings = warnings,
			infos = infos,
			qualificationErrors = qualificationErrors,
			qualificationWarnings = qualificationWarnings,
			qualificationInfos = qualificationInfos,
			signedBy = signedBy,
			signatureLevel = signatureLevel,
			signatureTime = signatureTime,
			certificate = certificate,
			signatureQualification = signatureQualification,
			trustTier = trustTier,
			hashAlgorithm = sigWrapper?.digestAlgorithm?.name,
			encryptionAlgorithm = sigWrapper?.encryptionAlgorithm?.name,
		)
	}
	
	/**
	 * Append warnings to a [SignatureValidationResult] when the signature's hash or
	 * encryption algorithm is in the disabled set.
	 *
	 * This serves as a safety net on top of the DSS policy patching performed in
	 * [AdESPolicy.load]: even when a custom policy file is loaded (which may not
	 * reflect the disabled sets), the user always sees an explicit warning.
	 */
	private fun annotateDisabledAlgorithms(
		sig: SignatureValidationResult,
		disabledHash: Set<HashAlgorithm>,
		disabledEncryption: Set<EncryptionAlgorithm>,
	): SignatureValidationResult {
		val extra = mutableListOf<String>()
		
		val sigHashName = sig.hashAlgorithm
		if (sigHashName != null) {
			val matched = disabledHash.find { it.dssName.equals(sigHashName, ignoreCase = true) }
			if (matched != null) {
				extra += "Hash algorithm $sigHashName is disabled in your configuration"
			}
		}
		
		val sigEncName = sig.encryptionAlgorithm
		if (sigEncName != null) {
			val matched = disabledEncryption.find { it.dssName.equals(sigEncName, ignoreCase = true) }
			if (matched != null) {
				extra += "Encryption algorithm $sigEncName is disabled in your configuration"
			}
		}
		
		return if (extra.isEmpty()) sig
		else sig.copy(warnings = sig.warnings + extra)
	}
	
	/**
	 * Extract all four raw DSS report XML strings from the [Reports] bundle
	 * so they can be carried on the domain [ValidationReport] for later export.
	 */
	private fun extractRawReports(reports: Reports): Map<RawReportFormat, String> = buildMap {
		reports.xmlDetailedReport?.let { put(RawReportFormat.XML_DETAILED, it) }
		reports.xmlSimpleReport?.let { put(RawReportFormat.XML_SIMPLE, it) }
		reports.xmlDiagnosticData?.let { put(RawReportFormat.XML_DIAGNOSTIC, it) }
		reports.xmlValidationReport?.let { put(RawReportFormat.XML_ETSI, it) }
	}

	/**
	 * Write the native DSS report in the requested [format] to [outputPath].
	 *
	 * Uses the pre-marshaled XML strings that [Reports] caches internally, so the
	 * output is identical to what the DSS webapp produces — no round-trip through the
	 * domain model.
	 */
	private fun writeRawReport(reports: Reports, outputPath: String, format: RawReportFormat) {
		val xml = when (format) {
			RawReportFormat.XML_DETAILED -> reports.xmlDetailedReport
			RawReportFormat.XML_SIMPLE -> reports.xmlSimpleReport
			RawReportFormat.XML_DIAGNOSTIC -> reports.xmlDiagnosticData
			RawReportFormat.XML_ETSI -> reports.xmlValidationReport
		}
		File(outputPath).also { it.parentFile?.mkdirs() }.writeText(xml)
	}
}
