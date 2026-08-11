package cz.pizavo.omnisign.data.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.ades.policy.AdESPolicy
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.toDomainOrNull
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.*
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import eu.europa.esig.dss.diagnostic.RevocationWrapper
import eu.europa.esig.dss.diagnostic.SignatureWrapper
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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import java.io.File
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JVM implementation of [ArchivingRepository] backed by the EU DSS library.
 *
 * Uses [PAdESService.extendDocument] to promote a signed PDF to any higher PAdES level:
 * - **B-T**: embeds an RFC 3161 document timestamp (requires a TSA endpoint).
 * - **B-LT**: additionally embeds CRL/OCSP revocation data.
 * - **B-LTA**: additionally applies an archival document timestamp covering the revocation data.
 *
 * All target levels ≥ B-T require a TSA endpoint in the resolved configuration.
 *
 * Extension uses [DssServiceFactory.buildExtendCertificateVerifier], which — unlike the signing
 * verifier — reports revocation data it could not obtain. For a target of B-LT or higher that
 * condition means the output did not reach the requested level, and it is surfaced as
 * [ArchivingResult.revocationDataMissing]; a B-T target needs no revocation data, so it never sets
 * the flag.
 */
class DssArchivingRepository(
	private val configRepository: ConfigRepository,
	private val dssServiceFactory: DssServiceFactory,
	private val warningSanitizer: DssWarningSanitizer,
	private val tspErrorDetector: TspErrorDetector,
	private val revocationErrorDetector: RevocationErrorDetector,
	private val documentInputErrorDetector: DocumentInputErrorDetector,
	private val trustStore: TrustStore,
	private val renewalCheckCache: RenewalCheckCache,
	private val signatureSpaceErrorDetector: SignatureSpaceErrorDetector,
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
				return ArchivingError.ExtensionFailed(LocalizableText.Literal(error.message)).left()
			}
			
			if (parameters.targetLevel == SignatureLevel.PADES_BASELINE_B) {
				return ArchivingError.targetLevelNotHigher().left()
			}
			
			val tsConfig = resolvedConfig.timestampServer
				?: return ArchivingError.timestampServerRequired(parameters.targetLevel.name).left()
			
			if (!documentInputErrorDetector.looksLikePdf(parameters.inputBytes)) {
				return ArchivingError.malformedPdf(details = "input has no %PDF- header").left()
			}

			val dssLevel = parameters.targetLevel.toDss()
			val statusAlert = CollectingStatusAlert()
			val logCapture = DssLogCapture()
			val (service, tlWarnings) = buildExtendService(resolvedConfig, tsConfig, statusAlert)
			val extendParams = PAdESSignatureParameters().apply {
				setSignatureLevel(dssLevel)
				dssServiceFactory.applyTimestampContentSize(this)
			}
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

				val achieved = detectAchievedLevel(outputBytes)

				ArchivingResult(
					outputBytes = outputBytes,
					outputName = parameters.inputName,
					newSignatureLevel = (achieved ?: parameters.targetLevel).name,
					annotatedWarnings = sanitized.annotatedSummaries,
					rawWarnings = sanitized.raw,
					revocationDataMissing = sanitized.longTermMaterialMissing &&
						parameters.targetLevel >= SignatureLevel.PADES_BASELINE_LT,
					achievedLevel = achieved,
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
					LocalizableText.Literal(tspErrorDetector.buildUserMessage(e, tsaUrl)),
					details = e.message,
					cause = e,
				).left()
			}
			
			if (documentInputErrorDetector.isEncrypted(e)) {
				return ArchivingError.pdfEncrypted(details = e.message, cause = e).left()
			}

			if (signatureSpaceErrorDetector.isSignatureTooLarge(e)) {
				return ArchivingError.timestampTooLarge(details = e.message, cause = e).left()
			}

			if (revocationErrorDetector.isRevocationException(e)) {
				ArchivingError.revocationInfoFailed(details = e.message, cause = e).left()
			} else {
				ArchivingError.extensionFailed(details = e.message, cause = e).left()
			}
		}
	}
	
	@Suppress("TooGenericExceptionCaught", "ReturnCount")
	override suspend fun needsArchivalRenewal(
		filePath: String,
		renewalBufferDays: Int,
	): OperationResult<RenewalAssessment> {
		return try {
			val file = File(filePath)
			if (!file.exists()) {
				return ArchivingError.fileNotFound(filePath).left()
			}

			val now = Clock.System.now()
			val renewalThreshold = now + renewalBufferDays.days

			val cached = renewalCheckCache.get(filePath)
			if (cached != null &&
				cached.sizeBytes == file.length() &&
				cached.lastModifiedMillis == file.lastModified()
			) {
				if (cached.terminal) {
					return RenewalAssessment.unrecoverable(RenewalReason.BELOW_LT, cached.earliestRenewalAt).right()
				}
				if (now < cached.earliestRenewalAt - renewalBufferDays.days) {
					return RenewalAssessment.notNeeded().right()
				}
			}

			val document = FileDocument(file)
			val validator = PDFDocumentValidator(document).apply {
				setCertificateVerifier(CommonCertificateVerifier())
				setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
			}
			val diagnosticData = validator.validateDocument().diagnosticData
			val signatures = diagnosticData.signatures

			if (signatures.isEmpty()) {
				renewalCheckCache.remove(filePath)
				return RenewalAssessment.noSignature().right()
			}

			val level = signatures.mapNotNull { it.signatureFormat?.toDomainOrNull() }.minOrNull()
			when {
				level == null || level < SignatureLevel.PADES_BASELINE_LT ->
					assessBelowLt(filePath, file, signatures, now)

				!signatures.all { hasUsableSigningCertificateRevocation(it) } ->
					assessUnusableRevocation(filePath, signatures)

				level == SignatureLevel.PADES_BASELINE_LT ->
					assessLt(filePath, signatures)

				else -> assessArchival(filePath, file, diagnosticData.getTimestampList(), renewalThreshold)
			}
		} catch (e: Exception) {
			ArchivingError.renewalCheckFailed(details = e.message, cause = e).left()
		}
	}

	/**
	 * Assess a document that carries no usable long-term validation material.
	 *
	 * The deadline is the earliest signing-certificate expiry across the document's signatures: the
	 * moment after which acceptable revocation data for that certificate can no longer be obtained.
	 * Before it, the step is due now — waiting gains nothing and the window only shrinks. After it,
	 * the document can never reach B-LT, so it is reported as [RenewalNeed.UNRECOVERABLE] rather
	 * than as work that a later run might complete.
	 *
	 * A signing certificate DSS could not resolve leaves the deadline unknown; that is reported as
	 * undeterminable rather than guessed either way, matching how an unresolvable TSA certificate is
	 * treated in [assessArchival].
	 */
	private fun assessBelowLt(
		filePath: String,
		file: File,
		signatures: List<SignatureWrapper>,
		now: Instant,
	): OperationResult<RenewalAssessment> {
		renewalCheckCache.remove(filePath)
		val expiries = signatures.map { it.signingCertificate?.notAfter?.toKotlinInstant() }
		if (expiries.any { it == null }) {
			return ArchivingError.renewalStatusUndeterminable(
				details = "$filePath is below B-LT and DSS could not resolve a signing certificate, " +
					"so the deadline for embedding revocation data — that certificate's expiry — is unknown",
			).left()
		}
		val deadline = expiries.filterNotNull().min()
		return if (deadline <= now) {
			renewalCheckCache.put(
				filePath,
				RenewalCheckCacheEntry(file.length(), file.lastModified(), deadline, terminal = true),
			)
			RenewalAssessment.unrecoverable(RenewalReason.BELOW_LT, deadline).right()
		} else {
			RenewalAssessment.needed(RenewalReason.BELOW_LT, deadline, deadlineIsFinal = true).right()
		}
	}

	/**
	 * Assess a document whose structural level is B-LT or higher but whose embedded revocation data
	 * cannot actually be used, so it must be replaced before anything is sealed over it.
	 *
	 * This is the case the level cannot see. DSS's baseline-LT requirement counts revocation binaries
	 * that are *present*; whether they are *acceptable* is decided separately, at validation time, by
	 * [eu.europa.esig.dss.spi.validation.RevocationDataVerifier]. A CRL fetched after the signing
	 * certificate expired satisfies the former and fails the latter, so a document can read as B-LT
	 * and still have nothing a validator will accept. Sealing it would freeze that.
	 *
	 * Reported as [RenewalReason.LT_REFRESH_NEEDED] rather than as terminal, even when the signing
	 * certificate has long expired and no newer data can help. The extension attempt is the better
	 * judge: it runs with trusted lists loaded and therefore sees the `expiredCertsRevocationInfo`
	 * service metadata that this offline check cannot, so a document this check doubts may still be
	 * promotable. If it is not, the attempt fails with
	 * [ArchivingResult.revocationDataMissing] and the original is left alone — an honest failure
	 * beats a premature "never".
	 */
	private fun assessUnusableRevocation(
		filePath: String,
		signatures: List<SignatureWrapper>,
	): OperationResult<RenewalAssessment> {
		renewalCheckCache.remove(filePath)
		val deadline = signatures.mapNotNull { it.signingCertificate?.notAfter?.toKotlinInstant() }.minOrNull()
		return RenewalAssessment.needed(RenewalReason.LT_REFRESH_NEEDED, deadline, deadlineIsFinal = true).right()
	}

	/**
	 * Whether any revocation data embedded for [signature]'s signing certificate is usable at all.
	 *
	 * Mirrors DSS's `RevocationHasInformationAboutCertificateCheck`, which is the rule that decides
	 * acceptability: revocation data counts for a certificate when a matching `certHash` proves the
	 * responder knew that exact certificate, or when the certificate had not yet expired at the time
	 * from which the issuer vouches for it. That time is the response's `thisUpdate`, brought earlier
	 * by a CRL `expiredCertsOnCRL` or an OCSP `archiveCutoff` extension when one is present — the
	 * issuer's explicit statement that it reports on certificates that expired that long ago.
	 *
	 * Deliberately does not consider the `expiredCertsRevocationInfo` extension a trusted list can
	 * carry, which DSS consults when neither response extension is present: this check runs offline
	 * with no trusted lists, by design. It can therefore be stricter than a full validation would be,
	 * which is why [assessUnusableRevocation] treats its verdict as "refresh" rather than "hopeless".
	 */
	private fun hasUsableSigningCertificateRevocation(signature: SignatureWrapper): Boolean {
		val certificate = signature.signingCertificate ?: return false
		val notAfter = certificate.notAfter ?: return false
		return certificate.certificateRevocationData.orEmpty().any { revocation ->
			val certHashProves = revocation.isCertHashExtensionPresent && revocation.isCertHashExtensionMatch
			certHashProves || !notAfter.before(vouchedForSince(revocation))
		}
	}

	/**
	 * The instant from which [revocation]'s issuer vouches for the certificate it covers: its
	 * `thisUpdate`, moved earlier by an `expiredCertsOnCRL` or `archiveCutoff` extension when one is
	 * present and does precede it, exactly as DSS resolves it.
	 */
	private fun vouchedForSince(revocation: RevocationWrapper): Date {
		var since = revocation.thisUpdate
		revocation.expiredCertsOnCRL?.takeIf { it.before(since) }?.let { since = it }
		revocation.archiveCutOff?.takeIf { it.before(since) }?.let { since = it }
		return since
	}

	/**
	 * Assess a B-LT document: revocation data is embedded, but nothing proves when it existed.
	 *
	 * Both outcomes are due now, and they differ in what has to happen. When the embedded revocation
	 * data predates the signature timestamp it covers nothing, so it has to be refreshed before
	 * anything is sealed over it ([RenewalReason.LT_REFRESH_NEEDED]); sealing first would freeze the
	 * gap. Otherwise the data is sound and only needs anchoring ([RenewalReason.LT_NOT_SEALED])
	 * before the earlier of its own `nextUpdate` and its issuer's expiry, after which it can no
	 * longer be validated on its own.
	 */
	private fun assessLt(
		filePath: String,
		signatures: List<SignatureWrapper>,
	): OperationResult<RenewalAssessment> {
		renewalCheckCache.remove(filePath)
		val stale = signatures.any { signature ->
			val bestSignatureTime = signature.signatureTimestamps
				.mapNotNull { it.productionTime?.toKotlinInstant() }
				.minOrNull()
			val newest = signature.signingCertificate?.certificateRevocationData
				?.mapNotNull { it.thisUpdate?.toKotlinInstant() }
				?.maxOrNull()
			bestSignatureTime != null && (newest == null || newest < bestSignatureTime)
		}
		return if (stale) {
			RenewalAssessment.needed(RenewalReason.LT_REFRESH_NEEDED, revocationHorizon(signatures)).right()
		} else {
			RenewalAssessment.needed(RenewalReason.LT_NOT_SEALED, revocationHorizon(signatures)).right()
		}
	}

	/**
	 * The instant after which the embedded revocation data of [signatures] can no longer be
	 * validated on its own: the earliest of each response's `nextUpdate` and the expiry of the
	 * certificate that signed it. `null` when neither could be read.
	 */
	private fun revocationHorizon(signatures: List<SignatureWrapper>): Instant? =
		signatures.flatMap { it.signingCertificate?.certificateRevocationData.orEmpty() }
			.flatMap { revocation ->
				listOfNotNull(
					revocation.nextUpdate?.toKotlinInstant(),
					revocation.signingCertificate?.notAfter?.toKotlinInstant(),
				)
			}
			.minOrNull()

	/**
	 * Assess a B-LTA document with the coverage-aware timestamp rule — the aging case, and the only
	 * one where waiting until a deadline approaches is correct, because the protection is complete
	 * and merely needs renewing before it ages out.
	 */
	private fun assessArchival(
		filePath: String,
		file: File,
		timestamps: List<TimestampWrapper>,
		renewalThreshold: Instant,
	): OperationResult<RenewalAssessment> =
		when (needsRenewal(timestamps, renewalThreshold, renewalCryptographicSuite)) {
			RenewalDecision.NEEDED -> {
				renewalCheckCache.remove(filePath)
				val due = earliestRenewalAt(timestamps, renewalCryptographicSuite)
				val reason = if (algorithmsDriveRenewal(timestamps, renewalThreshold)) {
					RenewalReason.ALGORITHM_WEAKENING
				} else {
					RenewalReason.TIMESTAMP_EXPIRING
				}
				RenewalAssessment.needed(reason, due).right()
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
				RenewalAssessment.notNeeded().right()
			}
			RenewalDecision.UNDETERMINABLE -> {
				renewalCheckCache.remove(filePath)
				ArchivingError.renewalStatusUndeterminable(details = "$filePath has a renewal-relevant timestamp whose signing (TSA) certificate — and thus its expiry — DSS could not resolve; the document may be missing the LT/LTA validation material required to assess it").left()
			}
		}

	/**
	 * Whether it is a weakening algorithm, rather than an expiring certificate, that drives renewal
	 * for [timestamps]. Used only to label the reason; [needsRenewal] has already decided.
	 */
	private fun algorithmsDriveRenewal(timestamps: List<TimestampWrapper>, renewalThreshold: Instant): Boolean =
		relevantTimestamps(timestamps).any {
			algorithmsExpireBefore(it, renewalCryptographicSuite, renewalThreshold)
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

	/**
	 * Inspect [inputBytes] for the timestamp and level state the extension dialog needs.
	 *
	 * [DocumentTimestampInfo.hasDocumentTimestamp] is a structural fact read with PDFBox, but the
	 * level — and with it [DocumentTimestampInfo.containsLtData] — comes from [detectAchievedLevel],
	 * so that the dialog reports what the document *is* rather than what its structure hints at.
	 *
	 * The presence of a `/DSS` dictionary is deliberately **not** consulted, not even as a fallback.
	 * That dictionary can be present and hold nothing usable — certificates but no revocation data,
	 * or nothing at all — which is precisely how a B-T document comes to be labelled B-LT by tools
	 * that read structure instead of content. An undeterminable level therefore yields
	 * `containsLtData = false`: the honest answer to "does this carry usable long-term validation
	 * material" when nothing could be established is no, and it errs toward offering the user more
	 * protection rather than less.
	 */
	@Suppress("TooGenericExceptionCaught")
	override suspend fun getDocumentTimestampInfo(inputBytes: ByteArray): OperationResult<DocumentTimestampInfo> {
		return try {
			Loader.loadPDF(inputBytes).use { pdf ->
				val hasDocumentTimestamp = pdf.signatureDictionaries.any { sig ->
					sig.subFilter == PADES_TIMESTAMP_SUBFILTER
				}
				
				val level = detectAchievedLevel(inputBytes)
				val containsLtData = level != null && level >= SignatureLevel.PADES_BASELINE_LT

				DocumentTimestampInfo(
					hasDocumentTimestamp = hasDocumentTimestamp,
					containsLtData = containsLtData,
					hasSignatureTimestamp = detectSignatureTimestamp(inputBytes),
					level = level,
					ltMaterialUsable = !containsLtData || hasUsableLtMaterial(inputBytes),
				).right()
			}
		} catch (e: Exception) {
			ArchivingError.timestampInspectFailed(details = e.message, cause = e).left()
		}
	}

	/**
	 * The PAdES baseline level [pdfBytes] actually reached, as DSS reads it back out of the document.
	 *
	 * Delegates to DSS's own `getDataFoundUpToLevel`, the same determination that drives the level
	 * shown in a validation report, so the two can never disagree. That matters most for the case
	 * this exists to catch: a B-LT augmentation that wrote a DSS dictionary holding certificates but
	 * no revocation data reports B-T here, because DSS's LT requirement is non-empty CRL/OCSP data
	 * for the chain, not the presence of the dictionary.
	 *
	 * Parses without validating — the certificate verifier carries no trust anchors and no online
	 * revocation sources, so nothing here reaches the network. It reuses the extension's own
	 * memory-spilling PDF factory, because it runs on a document the extension just produced and
	 * would otherwise load a large one entirely into the heap. When a document carries several
	 * signatures the lowest level wins: an archive is only as strong as its weakest signature.
	 *
	 * A failure here never fails the extension: the document exists and is sound, only its
	 * description could not be read back. The failure is logged rather than swallowed — DSS being
	 * unable to re-parse bytes it has just written is an anomaly worth seeing — and the `null` is
	 * carried through to [ArchivingResult.achievedLevel] so callers can tell "did not reach the
	 * level" from "could not be established", which are not the same thing.
	 *
	 * @param pdfBytes The produced document to inspect.
	 * @return The level reached, or `null` when the document could not be parsed, carries no
	 *   signature, or its level is outside the four PAdES baseline levels.
	 */
	@Suppress("TooGenericExceptionCaught")
	private fun detectAchievedLevel(pdfBytes: ByteArray): SignatureLevel? =
		try {
			PDFDocumentValidator(InMemoryDocument(pdfBytes))
				.apply {
					setCertificateVerifier(CommonCertificateVerifier())
					setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
				}
				.signatures
				.mapNotNull { it.dataFoundUpToLevel?.toDomainOrNull() }
				.minOrNull()
		} catch (e: Exception) {
			logger.warn(e) { "Could not read the achieved PAdES level back out of the extended document" }
			null
		}

	/**
	 * Whether the long-term validation material embedded in [pdfBytes] can be used, judged by the
	 * same rule the renewal assessment applies (see [hasUsableSigningCertificateRevocation]).
	 *
	 * Called only for a document that has such material, so the extra parse is paid where the answer
	 * changes what the dialog should offer: a document carrying revocation data no validator will
	 * accept needs that data refreshed, not sealed. Offline, like every other inspection here.
	 *
	 * A document that cannot be parsed is reported as usable rather than not: this drives a caveat in
	 * the UI, and inventing a warning out of a failed inspection would be worse than staying quiet —
	 * the level itself is already `null` in that case, which is the honest signal.
	 */
	@Suppress("TooGenericExceptionCaught")
	private fun hasUsableLtMaterial(pdfBytes: ByteArray): Boolean =
		try {
			PDFDocumentValidator(InMemoryDocument(pdfBytes))
				.apply {
					setCertificateVerifier(CommonCertificateVerifier())
					setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
				}
				.validateDocument().diagnosticData.signatures
				.all { hasUsableSigningCertificateRevocation(it) }
		} catch (e: Exception) {
			logger.warn(e) { "Could not judge whether the document's validation material is usable" }
			true
		}

	/**
	 * Whether any signature in [inputBytes] embeds a signature timestamp — the unsigned attribute
	 * that marks PAdES BASELINE-T.
	 *
	 * Parses the signatures with DSS but runs no validation: the certificate verifier carries no
	 * trust or online revocation sources, so no network lookups occur. This is what lets the dialog
	 * tell a B-B document (no timestamp) apart from a B-T one, which the PDF-structure flags in
	 * [getDocumentTimestampInfo] cannot. A parse failure degrades to `false` — the dialog then offers
	 * the B-B options, which is the safe direction — and is logged rather than swallowed, so the
	 * reason a document is mislabelled in the dialog is recoverable from the log.
	 *
	 * @param inputBytes The raw PDF bytes to inspect.
	 * @return `true` when at least one signature carries a signature timestamp.
	 */
	@Suppress("TooGenericExceptionCaught")
	private fun detectSignatureTimestamp(inputBytes: ByteArray): Boolean =
		try {
			val validator = PDFDocumentValidator(InMemoryDocument(inputBytes)).apply {
				setCertificateVerifier(CommonCertificateVerifier())
				setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
			}
			validator.signatures.any { it.signatureTimestamps.isNotEmpty() }
		} catch (e: Exception) {
			logger.warn(e) { "Could not inspect the document for a signature timestamp; treating it as absent" }
			false
		}

	/**
	 * Build a [PAdESService] wired for document extension with revocation and TSA sources.
	 *
	 * Loads EU LOTL and custom trusted-list sources together with the directly-trusted certificates
	 * resolved from the [TrustStore] for the active scope, so that TSA and certificate chains are
	 * properly trusted during the extension operation.
	 *
	 * Uses [DssServiceFactory.buildExtendCertificateVerifier] rather than the signing verifier, so
	 * that revocation data the target level needs but the extension could not obtain is reported
	 * instead of silently absent from the output.
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
		val (cv, tlWarnings) = dssServiceFactory.buildExtendCertificateVerifier(config, anchors) { statusAlert }
		val service = PAdESService(cv).apply {
			setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
			setTspSource(dssServiceFactory.buildTspSource(tsConfig))
		}
		return service to tlWarnings.map { it.english() }
	}
	
	companion object {
		/**
		 * PDF SubFilter value identifying a PAdES document timestamp (RFC 3161).
		 */
		private const val PADES_TIMESTAMP_SUBFILTER = "ETSI.RFC3161"
	}
}

