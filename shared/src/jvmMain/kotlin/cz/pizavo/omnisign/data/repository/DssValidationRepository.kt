package cz.pizavo.omnisign.data.repository

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.ades.policy.AdESPolicy
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.TimestampValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import eu.europa.esig.dss.detailedreport.DetailedReport
import eu.europa.esig.dss.diagnostic.DiagnosticData
import eu.europa.esig.dss.enumerations.Indication
import eu.europa.esig.dss.enumerations.SignatureQualification
import eu.europa.esig.dss.enumerations.TimestampQualification
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader
import eu.europa.esig.dss.simplereport.SimpleReport
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.tsl.source.TLSource
import eu.europa.esig.dss.validation.SignedDocumentValidator
import eu.europa.esig.dss.validation.reports.Reports
import java.io.File
import java.nio.file.Files

/**
 * JVM implementation of [ValidationRepository] using the EU DSS library.
 *
 * Builds a [CommonCertificateVerifier] with online CRL/OCSP sources, AIA support,
 * and optional EU LOTL or custom trusted lists — all driven by the [ResolvedConfig]
 * supplied in [ValidationParameters].
 */
class DssValidationRepository : ValidationRepository {
	
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
			
			val validator = SignedDocumentValidator.fromDocument(FileDocument(file))
				.apply {
					setCertificateVerifier(buildCertificateVerifier(parameters.resolvedConfig))
					if (this is PDFDocumentValidator) {
						setPdfObjFactory(DssServiceFactory.buildPdfObjectFactory())
					}
				}
			
			
			val reports = resolveValidationPolicy(parameters.resolvedConfig, parameters.customPolicyPath)
				?.let { validator.validateDocument(it) }
				?: validator.validateDocument()
			
			convertReports(reports, file.name)
		}.mapLeft { exception ->
			ValidationError.ValidationFailed(
				message = "Validation failed",
				details = exception.message,
				cause = exception
			)
		}
	}
	
	/**
	 * Build a fully configured [CommonCertificateVerifier].
	 *
	 * When [config] is null or revocation checking is disabled, returns a lenient
	 * offline verifier suitable for basic structural validation.
	 */
	private fun buildCertificateVerifier(config: ResolvedConfig?): CommonCertificateVerifier {
		var capturedLoader: CommonsDataLoader? = null

		val cv = DssServiceFactory.buildCertificateVerifier(config) { capturedLoader = it }

		if (config != null && (config.validation.useEuLotl || config.validation.customTrustedLists.isNotEmpty())) {
			val dataLoader = capturedLoader ?: CommonsDataLoader()
			val tlCertSource = TrustedListsCertificateSource()
			buildTLValidationJob(config, tlCertSource, dataLoader).onlineRefresh()
			cv.setTrustedCertSources(tlCertSource)
		}

		return cv
	}
	
	/**
	 * Build and return a [TLValidationJob] wired with EU LOTL and/or custom TL sources.
	 */
	private fun buildTLValidationJob(
		config: ResolvedConfig,
		tlCertSource: TrustedListsCertificateSource,
		dataLoader: CommonsDataLoader
	): TLValidationJob {
		val cacheDir = Files.createTempDirectory("omnisign-tl-cache").toFile()

		val onlineLoader = FileCacheDataLoader().apply {
			setCacheExpirationTime(0)
			setDataLoader(dataLoader)
			setFileCacheDirectory(cacheDir)
		}

		val job = TLValidationJob().apply {
			setTrustedListCertificateSource(tlCertSource)
			setOnlineDataLoader(onlineLoader)
		}

		if (config.validation.useEuLotl) {
			val lotlSource = LOTLSource().apply {
				url = EU_LOTL_URL
				certificateSource = buildOjCertificateSource()
				isPivotSupport = true
			}
			job.setListOfTrustedListSources(lotlSource)
		}

		if (config.validation.customTrustedLists.isNotEmpty()) {
			val tlSources = config.validation.customTrustedLists.map { tl ->
				TLSource().apply {
					url = tl.source
					tl.signingCertPath?.let { certPath ->
						certificateSource = buildCertSourceFromFile(certPath)
					}
				}
			}.toTypedArray()
			job.setTrustedListSources(*tlSources)
		}

		return job
	}

	/**
	 * Load the Official Journal (OJ) keystore bundled as a classpath resource and wrap it
	 * in a [CommonTrustedCertificateSource] so DSS can verify EU LOTL pivot signatures.
	 *
	 * The keystore is the pre-configured one from the dss-demonstrations repository and
	 * contains the EC's LOTL signing certificates published in the Official Journal.
	 */
	private fun buildOjCertificateSource(): CommonTrustedCertificateSource {
		val keystoreStream = DssValidationRepository::class.java
			.getResourceAsStream(OJ_KEYSTORE_RESOURCE)
			?: error("OJ keystore not found on classpath: $OJ_KEYSTORE_RESOURCE")

		val keystore = KeyStoreCertificateSource(keystoreStream, OJ_KEYSTORE_TYPE, OJ_KEYSTORE_PASSWORD.toCharArray())
		return CommonTrustedCertificateSource().also { it.importAsTrusted(keystore) }
	}

	/**
	 * Build a [CommonTrustedCertificateSource] from a PEM or DER certificate file on disk.
	 * Used to supply per-TL signing certificates for custom [TLSource] instances.
	 */
	private fun buildCertSourceFromFile(certPath: String): CommonTrustedCertificateSource {
		val x509 = java.security.cert.CertificateFactory.getInstance("X.509")
			.generateCertificate(File(certPath).inputStream()) as java.security.cert.X509Certificate
		val token = eu.europa.esig.dss.model.x509.CertificateToken(x509)
		return CommonTrustedCertificateSource().also { it.addCertificate(token) }
	}

	/**
	 * Load a [eu.europa.esig.dss.model.policy.ValidationPolicy] from the resolved config
	 * or a custom policy path. Returns null to let DSS use its built-in default ETSI policy.
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
		return policyFile?.let { adeSPolicy.load(it) }
	}
	
	/**
	 * Convert DSS [Reports] into our domain [ValidationReport].
	 */
	private fun convertReports(reports: Reports, documentName: String): ValidationReport {
		val simpleReport = reports.simpleReport
		val detailedReport = reports.detailedReport
		val diagnosticData = reports.diagnosticData

		val signatures = simpleReport.signatureIdList.map { id ->
			convertSignature(simpleReport, diagnosticData, id)
		}

		val timestamps = diagnosticData.getTimestampList().map { tsw ->
			convertTimestamp(tsw, detailedReport)
		}

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
			timestamps = timestamps
		)
	}

	/**
	 * Convert a single DSS [eu.europa.esig.dss.diagnostic.TimestampWrapper] into a [TimestampValidationResult].
	 *
	 * Uses [DetailedReport.getBasicTimestampValidationIndication] for the indication,
	 * [DetailedReport.getTimestampQualification] for the QTSA qualification level, and
	 * [DetailedReport.getBasicBuildingBlockById] for errors, warnings, and informational messages.
	 */
	private fun convertTimestamp(
		tsw: eu.europa.esig.dss.diagnostic.TimestampWrapper,
		detailedReport: DetailedReport
	): TimestampValidationResult {
		val id = tsw.id

		val rawIndication = detailedReport.getBasicTimestampValidationIndication(id)
		val indication = when (rawIndication) {
			Indication.TOTAL_PASSED -> ValidationIndication.TOTAL_PASSED
			Indication.TOTAL_FAILED -> ValidationIndication.TOTAL_FAILED
			else -> ValidationIndication.INDETERMINATE
		}

		val subIndication = detailedReport.getBasicTimestampValidationSubIndication(id)?.toString()

		val qualification = try {
			detailedReport.getTimestampQualification(id)
				?.takeIf { it != TimestampQualification.NA }
				?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
		} catch (_: Exception) {
			null
		}

		val bbb = detailedReport.getBasicBuildingBlockById(id)
		val errors = bbb?.conclusion?.errors?.map { it.value } ?: emptyList()
		val warnings = bbb?.conclusion?.warnings?.map { it.value } ?: emptyList()
		val infos = bbb?.conclusion?.infos?.map { it.value } ?: emptyList()

		val tsaSubjectDN = tsw.signingCertificate?.getCertificateDN()

		return TimestampValidationResult(
			timestampId = id,
			type = tsw.type?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unknown",
			indication = indication,
			subIndication = subIndication,
			productionTime = tsw.productionTime?.toString() ?: "Unknown",
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
			Indication.TOTAL_PASSED -> ValidationIndication.TOTAL_PASSED
			Indication.TOTAL_FAILED -> ValidationIndication.TOTAL_FAILED
			else -> ValidationIndication.INDETERMINATE
		}
		
		val errors = (simpleReport.getAdESValidationErrors(signatureId) +
				simpleReport.getQualificationErrors(signatureId)).map { it.value }
		val warnings = (simpleReport.getAdESValidationWarnings(signatureId) +
				simpleReport.getQualificationWarnings(signatureId)).map { it.value }
		val infos = (simpleReport.getAdESValidationInfo(signatureId) +
				simpleReport.getQualificationInfo(signatureId)).map { it.value }
		
		val signedBy = simpleReport.getSignedBy(signatureId) ?: "Unknown"
		val signatureLevel = simpleReport.getSignatureFormat(signatureId)?.toString() ?: "Unknown"
		val signatureTime = simpleReport.getBestSignatureTime(signatureId)?.toString() ?: "Unknown"
		
		val sigWrapper = diagnosticData.getSignatureById(signatureId)
		val signingCert = sigWrapper?.signingCertificate
		
		val sha256Fingerprint = signingCert?.digestAlgoAndValue?.digestValue?.let { bytes ->
			bytes.joinToString(":") { "%02X".format(it) }
		}
		
		val certificate = CertificateInfo(
			subjectDN = signingCert?.getCertificateDN() ?: signedBy,
			issuerDN = signingCert?.getCertificateIssuerDN() ?: "Unknown",
			serialNumber = signingCert?.serialNumber ?: "Unknown",
			validFrom = signingCert?.notBefore?.toString() ?: "Unknown",
			validTo = signingCert?.notAfter?.toString() ?: "Unknown",
			keyUsages = signingCert?.keyUsages?.map { it.name } ?: emptyList(),
			isQualified = simpleReport.getSignatureQualification(signatureId)
				?.let { it != SignatureQualification.NA } ?: false,
			publicKeyAlgorithm = signingCert?.encryptionAlgorithm?.name,
			sha256Fingerprint = sha256Fingerprint,
		)
		
		val signatureQualification = simpleReport.getSignatureQualification(signatureId)
			?.takeIf { it != SignatureQualification.NA }
			?.readable
		
		return SignatureValidationResult(
			signatureId = signatureId,
			indication = indication,
			subIndication = simpleReport.getSubIndication(signatureId)?.toString(),
			errors = errors,
			warnings = warnings,
			infos = infos,
			signedBy = signedBy,
			signatureLevel = signatureLevel,
			signatureTime = signatureTime,
			certificate = certificate,
			signatureQualification = signatureQualification,
			hashAlgorithm = sigWrapper?.digestAlgorithm?.name,
			encryptionAlgorithm = sigWrapper?.encryptionAlgorithm?.name,
		)
	}
	
	private companion object {
		const val EU_LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml"

		const val OJ_KEYSTORE_RESOURCE = "/lotl-keystore.p12"
		const val OJ_KEYSTORE_TYPE = "PKCS12"
		const val OJ_KEYSTORE_PASSWORD = "dss-password"
	}
}
