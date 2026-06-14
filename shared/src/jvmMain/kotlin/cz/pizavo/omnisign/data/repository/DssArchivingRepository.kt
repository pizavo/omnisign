package cz.pizavo.omnisign.data.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import eu.europa.esig.dss.diagnostic.TimestampWrapper
import eu.europa.esig.dss.enumerations.TimestampType
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * JVM implementation of [ArchivingRepository] backed by the EU DSS library.
 *
 * Uses [PAdESService.extendDocument] to promote a signed PDF to any higher PAdES level:
 * - **B-T**: embeds an RFC 3161 document timestamp (requires a TSA endpoint).
 * - **B-LT**: additionally embeds CRL/OCSP revocation data.
 * - **B-LTA**: additionally applies an archival document timestamp covering the revocation data.
 *
 * All target levels ≥ B-T require a TSA endpoint in the resolved configuration.
 */
class DssArchivingRepository(
	private val configRepository: ConfigRepository,
	private val dssServiceFactory: DssServiceFactory,
	private val warningSanitizer: DssWarningSanitizer,
	private val tspErrorDetector: TspErrorDetector,
	private val trustStore: TrustStore,
) : ArchivingRepository {
	
	@Suppress("TooGenericExceptionCaught", "ReturnCount")
	override suspend fun extendDocument(parameters: ArchivingParameters): OperationResult<ArchivingResult> {
		return try {
			val config = configRepository.getCurrentConfig()
			val resolvedConfig = parameters.resolvedConfig ?: ResolvedConfig.resolve(
				global = config.global,
				profile = config.activeProfile?.let { config.profiles[it] },
				operationOverrides = null
			).getOrElse { error ->
				return ArchivingError.ExtensionFailed(
					message = error.message
				).left()
			}
			
			if (parameters.targetLevel == SignatureLevel.PADES_BASELINE_B) {
				return ArchivingError.ExtensionFailed(
					message = "Cannot extend to B-B: target level must be higher than the current level"
				).left()
			}
			
			val tsConfig = resolvedConfig.timestampServer
				?: return ArchivingError.ExtensionFailed(
					message = "A timestamp server must be configured for extension to ${parameters.targetLevel}"
				).left()
			
			val dssLevel = parameters.targetLevel.toDss()
			val statusAlert = CollectingStatusAlert()
			val logCapture = DssLogCapture()
			val (service, tlWarnings) = buildExtendService(resolvedConfig, tsConfig, statusAlert)
			val extendParams = PAdESSignatureParameters().apply { setSignatureLevel(dssLevel) }
			logCapture.start()
			try {
				val extendedDocument = service.extendDocument(
					InMemoryDocument(parameters.inputBytes, parameters.inputName), extendParams,
				)

				val warnings = tlWarnings + statusAlert.drain() + logCapture.stop()
				val sanitized = warningSanitizer.sanitize(warnings)

				val outputBytes = withContext(Dispatchers.IO) {
					extendedDocument.openStream().use { it.readAllBytes() }
				}

				ArchivingResult(
					outputBytes = outputBytes,
					outputName = parameters.inputName,
					newSignatureLevel = parameters.targetLevel.name,
					annotatedWarnings = sanitized.annotatedSummaries,
					rawWarnings = sanitized.raw,
				).right()
			} finally {
				logCapture.stop()
			}
		} catch (e: Exception) {
			if (tspErrorDetector.isTspException(e)) {
				val tsaUrl = (parameters.resolvedConfig ?: ResolvedConfig.resolve(
					global = configRepository.getCurrentConfig().global,
					profile = null,
					operationOverrides = null
				).getOrNull())?.timestampServer?.url
				return ArchivingError.TimestampFailed(
					message = tspErrorDetector.buildUserMessage(e, tsaUrl),
					details = e.message,
					cause = e,
				).left()
			}
			
			val isRevocationError = e.message?.let {
				it.contains("revocation", ignoreCase = true) ||
						it.contains("OCSP", ignoreCase = true) ||
						it.contains("CRL", ignoreCase = true)
			} ?: false
			
			if (isRevocationError) {
				ArchivingError.RevocationInfoError(
					message = "Failed to obtain revocation information",
					details = e.message,
					cause = e
				).left()
			} else {
				ArchivingError.ExtensionFailed(
					message = "Document extension failed",
					details = e.message,
					cause = e
				).left()
			}
		}
	}
	
	@Suppress("TooGenericExceptionCaught", "ReturnCount")
	override suspend fun needsArchivalRenewal(
		filePath: String,
		renewalBufferDays: Int,
	): OperationResult<Boolean> {
		return try {
			val file = File(filePath)
			if (!file.exists()) {
				return ArchivingError.ExtensionFailed(
					message = "File not found: $filePath"
				).left()
			}
			
			val document = FileDocument(file)
			val validator = PDFDocumentValidator(document).apply {
				setCertificateVerifier(CommonCertificateVerifier())
			}
			val diagnosticData = validator.validateDocument().diagnosticData
			val renewalThreshold = Clock.System.now() + renewalBufferDays.days

			needsRenewal(diagnosticData.getTimestampList(), renewalThreshold).right()
		} catch (e: Exception) {
			ArchivingError.ExtensionFailed(
				message = "Failed to check archival renewal status",
				details = e.message,
				cause = e
			).left()
		}
	}

	/**
	 * Decide whether [timestamps] — the full timestamp list of a validated PAdES document — call
	 * for archival re-timestamping against [renewalThreshold].
	 *
	 * The rule is **coverage-aware**: only a timestamp that no other timestamp seals can drive a
	 * renewal. Renewal is triggered when an *uncovered* signature- or document-timestamp has a
	 * signing (TSA) certificate expiring before [renewalThreshold]. This single predicate collapses
	 * the three renewal cases:
	 *
	 * 1. the outermost document timestamp (the B-LTA seal, which nothing covers) is itself aging;
	 * 2. a B-LT document with no document timestamp yet has an aging signature timestamp;
	 * 3. a signature timestamp applied after the last archival timestamp — and therefore not sealed
	 *    by it — is aging.
	 *
	 * Timestamps already sealed by a current document timestamp are deliberately ignored: that seal
	 * carries their proof-of-existence, so re-timestamping them would grow the file on every
	 * scheduler run without adding protection. In a PAdES archival chain each document timestamp
	 * covers every earlier token, so an aged inner timestamp never re-triggers renewal once a fresher
	 * seal exists.
	 *
	 * @param timestamps All timestamps DSS reported for the document, in any order.
	 * @param renewalThreshold The instant (now + renewal buffer) a certificate must outlast to be
	 *   considered safe.
	 * @return `true` if at least one uncovered, renewal-relevant timestamp expires within the window.
	 */
	internal fun needsRenewal(
		timestamps: List<TimestampWrapper>,
		renewalThreshold: Instant,
	): Boolean {
		val coveredTimestampIds = timestamps.flatMapTo(mutableSetOf()) { seal ->
			seal.timestampedTimestamps.map { it.id }
		}
		return timestamps.any { timestamp ->
			timestamp.id !in coveredTimestampIds &&
				drivesArchivalRenewal(timestamp) &&
				signingCertificateExpiresBefore(timestamp, renewalThreshold)
		}
	}

	/**
	 * Whether [timestamp] is a timestamp type whose expiry can drive archival renewal: a PAdES
	 * signature timestamp (B-T) or a document/archive timestamp (the B-LTA seal). Content-,
	 * validation-data- and VRI-timestamps never trigger renewal on their own.
	 */
	private fun drivesArchivalRenewal(timestamp: TimestampWrapper): Boolean =
		timestamp.type == TimestampType.SIGNATURE_TIMESTAMP ||
			timestamp.type == TimestampType.DOCUMENT_TIMESTAMP ||
			timestamp.type == TimestampType.ARCHIVE_TIMESTAMP

	/**
	 * Whether [timestamp]'s signing (TSA) certificate expires strictly before [renewalThreshold].
	 * A timestamp whose signing certificate DSS could not resolve cannot anchor a renewal decision
	 * and is treated as not-expiring.
	 */
	private fun signingCertificateExpiresBefore(
		timestamp: TimestampWrapper,
		renewalThreshold: Instant,
	): Boolean {
		val notAfter = timestamp.signingCertificate?.notAfter ?: return false
		return notAfter.toKotlinInstant() < renewalThreshold
	}
	
	@Suppress("TooGenericExceptionCaught")
	override suspend fun getDocumentTimestampInfo(inputBytes: ByteArray): OperationResult<DocumentTimestampInfo> {
		return try {
			Loader.loadPDF(inputBytes).use { pdf ->
				val hasDocumentTimestamp = pdf.signatureDictionaries.any { sig ->
					sig.subFilter == PADES_TIMESTAMP_SUBFILTER
				}
				
				val hasDssDictionary = pdf.documentCatalog
					.cosObject.containsKey(COSName.getPDFName(DSS_DICTIONARY_KEY))
				
				val containsLtData = hasDocumentTimestamp || hasDssDictionary
				
				DocumentTimestampInfo(
					hasDocumentTimestamp = hasDocumentTimestamp,
					containsLtData = containsLtData,
				).right()
			}
		} catch (e: Exception) {
			ArchivingError.ExtensionFailed(
				message = "Failed to inspect document timestamp state",
				details = e.message,
				cause = e
			).left()
		}
	}
	
	/**
	 * Build a [PAdESService] wired for document extension with revocation and TSA sources.
	 *
	 * Loads EU LOTL and custom trusted-list sources together with the directly-trusted certificates
	 * resolved from the [TrustStore] for the active scope, so that TSA and certificate chains are
	 * properly trusted during the extension operation.
	 *
	 * @param statusAlert A [CollectingStatusAlert] that will capture verifier warnings
	 *   fired during the extension operation.
	 * @return A pair of the wired [PAdESService] and any TL-loading warnings.
	 */
	private suspend fun buildExtendService(
		config: ResolvedConfig,
		tsConfig: TimestampServerConfig,
		statusAlert: CollectingStatusAlert,
	): Pair<PAdESService, List<String>> {
		val anchors = trustStore.resolve(TrustScope.of(config.profileName)).getOrElse { emptyList() }
		val (cv, tlWarnings) = dssServiceFactory.buildSigningCertificateVerifier(config, anchors) { statusAlert }
		val service = PAdESService(cv).apply {
			setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
			setTspSource(dssServiceFactory.buildTspSource(tsConfig))
		}
		return service to tlWarnings
	}
	
	companion object {
		/**
		 * PDF SubFilter value identifying a PAdES document timestamp (RFC 3161).
		 */
		private const val PADES_TIMESTAMP_SUBFILTER = "ETSI.RFC3161"
		
		/**
		 * PDF catalog key for the DSS dictionary that carries CRL/OCSP revocation data
		 * in PAdES-BASELINE-LT and higher.
		 */
		private const val DSS_DICTIONARY_KEY = "DSS"
	}
}

