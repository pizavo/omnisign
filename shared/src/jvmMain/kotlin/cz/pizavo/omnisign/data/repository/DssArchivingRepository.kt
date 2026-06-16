package cz.pizavo.omnisign.data.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.ades.policy.AdESPolicy
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
import cz.pizavo.omnisign.domain.model.result.RenewalCheckCacheEntry
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import eu.europa.esig.dss.diagnostic.TimestampWrapper
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm
import eu.europa.esig.dss.enumerations.TimestampType
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.policy.CryptographicSuite
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.validation.policy.CryptographicSuiteUtils
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
	private val renewalCheckCache: RenewalCheckCache,
) : ArchivingRepository {
	
	private val adesPolicy = AdESPolicy()

	/**
	 * The DSS cryptographic suite (the bundled ETSI schedule) used to decide whether a timestamp's
	 * algorithms have aged out. Built once and reused across files; null when the policy cannot be
	 * loaded, in which case algorithm obsolescence is simply not evaluated.
	 */
	private val renewalCryptographicSuite: CryptographicSuite? by lazy {
		runCatching { adesPolicy.cryptographicSuite() }.getOrNull()
	}

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
			
			val now = Clock.System.now()
			val renewalThreshold = now + renewalBufferDays.days

			val cached = renewalCheckCache.get(filePath)
			if (cached != null &&
				cached.sizeBytes == file.length() &&
				cached.lastModifiedMillis == file.lastModified() &&
				now < cached.earliestRenewalAt - renewalBufferDays.days
			) {
				return false.right()
			}

			val document = FileDocument(file)
			val validator = PDFDocumentValidator(document).apply {
				setCertificateVerifier(CommonCertificateVerifier())
			}
			val timestamps = validator.validateDocument().diagnosticData.getTimestampList()

			when (needsRenewal(timestamps, renewalThreshold, renewalCryptographicSuite)) {
				RenewalDecision.NEEDED -> {
					renewalCheckCache.remove(filePath)
					true.right()
				}
				RenewalDecision.NOT_NEEDED -> {
					val due = earliestRenewalAt(timestamps, renewalCryptographicSuite)
					if (due != null) {
						renewalCheckCache.put(
							filePath,
							RenewalCheckCacheEntry(file.length(), file.lastModified(), due),
						)
					} else {
						renewalCheckCache.remove(filePath)
					}
					false.right()
				}
				RenewalDecision.UNDETERMINABLE -> {
					renewalCheckCache.remove(filePath)
					ArchivingError.RenewalStatusUndeterminable(
						message = "Cannot determine whether the document needs renewal: a timestamp's signing certificate could not be resolved",
						details = "$filePath has a renewal-relevant timestamp whose signing (TSA) certificate — and thus its expiry — DSS could not resolve; the document may be missing the LT/LTA validation material required to assess it",
					).left()
				}
			}
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
	 * renewal. An *uncovered* signature- or document-timestamp triggers renewal when either its
	 * signing (TSA) certificate expires before [renewalThreshold], or one of its cryptographic
	 * algorithms — the message-imprint digest, the TSA signature digest, or the TSA signature
	 * algorithm with its key size — is no longer acceptable or expires before [renewalThreshold]
	 * under [cryptographicSuite]. This collapses the renewal cases:
	 *
	 * 1. the outermost document timestamp (the B-LTA seal, which nothing covers) is itself aging;
	 * 2. a B-LT document with no document timestamp yet has an aging signature timestamp;
	 * 3. a signature timestamp applied after the last archival timestamp — and therefore not sealed
	 *    by it — is aging;
	 * 4. any of the above is sealed with a hash or signature algorithm that has weakened.
	 *
	 * Timestamps already sealed by a current document timestamp are deliberately ignored: that seal
	 * carries their proof-of-existence, so re-timestamping them would grow the file on every
	 * scheduler run without adding protection. In a PAdES archival chain each document timestamp
	 * covers every earlier token, so an aged inner timestamp never re-triggers renewal once a fresher
	 * seal exists.
	 *
	 * @param timestamps All timestamps DSS reported for the document, in any order.
	 * @param renewalThreshold The instant (now + renewal buffer) a certificate or algorithm must
	 *   outlast to be considered safe.
	 * @param cryptographicSuite The cryptographic schedule used to judge algorithm obsolescence, or
	 *   null to skip the algorithm check and decide on certificate expiry alone.
	 * @return [RenewalDecision.NEEDED] when at least one uncovered, renewal-relevant timestamp has an
	 *   expiring certificate or a weakening algorithm; [RenewalDecision.UNDETERMINABLE] when none does
	 *   but one has an unresolvable signing certificate; otherwise [RenewalDecision.NOT_NEEDED].
	 */
	internal fun needsRenewal(
		timestamps: List<TimestampWrapper>,
		renewalThreshold: Instant,
		cryptographicSuite: CryptographicSuite?,
	): RenewalDecision {
		val relevant = relevantTimestamps(timestamps)
		val needed = relevant.any { timestamp ->
			val notAfter = signingCertificateNotAfter(timestamp)
			(notAfter != null && notAfter < renewalThreshold) ||
				algorithmsExpireBefore(timestamp, cryptographicSuite, renewalThreshold)
		}
		return when {
			needed -> RenewalDecision.NEEDED
			relevant.any { signingCertificateNotAfter(it) == null } -> RenewalDecision.UNDETERMINABLE
			else -> RenewalDecision.NOT_NEEDED
		}
	}

	/**
	 * The timestamps that can drive archival renewal: those no other timestamp seals (uncovered)
	 * whose type is a signature- or document/archive-timestamp. Covered timestamps are excluded
	 * because a current document timestamp already carries their proof-of-existence.
	 */
	private fun relevantTimestamps(timestamps: List<TimestampWrapper>): List<TimestampWrapper> {
		val coveredTimestampIds = timestamps.flatMapTo(mutableSetOf()) { seal ->
			seal.timestampedTimestamps.map { it.id }
		}
		return timestamps.filter { it.id !in coveredTimestampIds && drivesArchivalRenewal(it) }
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
	 * The `notAfter` instant of [timestamp]'s signing (TSA) certificate, or `null` when DSS could not
	 * resolve that certificate — and therefore its expiry is unknown. A conformant PAdES LT/LTA
	 * archive embeds the validation material needed to resolve it, so `null` marks a non-conformant or
	 * lower-level document rather than a safe one, and [needsRenewal] reports it as
	 * [RenewalDecision.UNDETERMINABLE] instead of silently treating it as not-expiring.
	 */
	private fun signingCertificateNotAfter(timestamp: TimestampWrapper): Instant? =
		timestamp.signingCertificate?.notAfter?.toKotlinInstant()

	/**
	 * Whether any cryptographic algorithm protecting [timestamp] is no longer acceptable, or expires
	 * before [renewalThreshold], under [suite]: the message-imprint digest (the hash binding the
	 * timestamp to the data), the TSA signature digest, and the TSA signature algorithm with its key
	 * size. When [suite] is null the algorithms cannot be judged and this returns false, leaving the
	 * certificate-expiry rule to decide.
	 */
	private fun algorithmsExpireBefore(
		timestamp: TimestampWrapper,
		suite: CryptographicSuite?,
		renewalThreshold: Instant,
	): Boolean {
		if (suite == null) return false
		val digests = listOfNotNull(timestamp.messageImprint?.digestMethod, timestamp.digestAlgorithm)
		val encryption = timestamp.encryptionAlgorithm
		return digests.any { digestExpiresBefore(suite, it, renewalThreshold) } ||
			(encryption != null &&
				encryptionExpiresBefore(suite, encryption, timestamp.keyLengthUsedToSignThisToken, renewalThreshold))
	}

	/**
	 * Whether [digest] is no longer acceptable under [suite], or its expiration date precedes
	 * [renewalThreshold].
	 */
	private fun digestExpiresBefore(
		suite: CryptographicSuite,
		digest: DigestAlgorithm,
		renewalThreshold: Instant,
	): Boolean =
		!CryptographicSuiteUtils.isDigestAlgorithmReliable(suite, digest) ||
			(CryptographicSuiteUtils.getExpirationDate(suite, digest)?.toKotlinInstant()
				?.let { it < renewalThreshold } == true)

	/**
	 * Whether [encryption] at [keyLength] bits is no longer acceptable under [suite], or its
	 * expiration date precedes [renewalThreshold]. A missing or non-numeric [keyLength] cannot be
	 * judged and is treated as not expiring.
	 */
	private fun encryptionExpiresBefore(
		suite: CryptographicSuite,
		encryption: EncryptionAlgorithm,
		keyLength: String?,
		renewalThreshold: Instant,
	): Boolean {
		val keySize = keyLength?.toIntOrNull()?.takeIf { it > 0 } ?: return false
		return !CryptographicSuiteUtils.isEncryptionAlgorithmWithKeySizeReliable(suite, encryption, keySize) ||
			(CryptographicSuiteUtils.getExpirationDate(suite, encryption, keySize)?.toKotlinInstant()
				?.let { it < renewalThreshold } == true)
	}
	
	/**
	 * The expiration instants of [timestamp]'s cryptographic algorithms under [suite] — the
	 * message-imprint digest, the TSA signature digest, and the TSA signature algorithm with its key
	 * size — for those that have a defined expiry. Algorithms with no expiry are omitted, and a null
	 * [suite] yields an empty list.
	 */
	private fun algorithmExpiries(timestamp: TimestampWrapper, suite: CryptographicSuite?): List<Instant> {
		if (suite == null) return emptyList()
		val instants = mutableListOf<Instant>()
		for (digest in listOfNotNull(timestamp.messageImprint?.digestMethod, timestamp.digestAlgorithm)) {
			CryptographicSuiteUtils.getExpirationDate(suite, digest)?.toKotlinInstant()?.let { instants += it }
		}
		val encryption = timestamp.encryptionAlgorithm
		val keySize = timestamp.keyLengthUsedToSignThisToken?.toIntOrNull()?.takeIf { it > 0 }
		if (encryption != null && keySize != null) {
			CryptographicSuiteUtils.getExpirationDate(suite, encryption, keySize)?.toKotlinInstant()?.let { instants += it }
		}
		return instants
	}

	/**
	 * The earliest instant at which [timestamps] will need archival renewal: the soonest expiry —
	 * signing-certificate or algorithm — among the uncovered, renewal-relevant timestamps, judged
	 * with [suite]. Returns `null` when no determinable due date exists: either nothing drives
	 * renewal, or a relevant timestamp's signing certificate is unresolvable. Used to cache how long
	 * a not-yet-due document may be skipped; stays consistent with [needsRenewal], which reports
	 * renewal as needed once the renewal threshold reaches this instant.
	 */
	@Suppress("ReturnCount")
	internal fun earliestRenewalAt(timestamps: List<TimestampWrapper>, suite: CryptographicSuite?): Instant? {
		val relevant = relevantTimestamps(timestamps)
		if (relevant.isEmpty()) return null
		return relevant.minOf { timestamp ->
			val notAfter = signingCertificateNotAfter(timestamp) ?: return null
			(listOf(notAfter) + algorithmExpiries(timestamp, suite)).min()
		}
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

