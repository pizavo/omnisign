package cz.pizavo.omnisign.data.repository

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import cz.pizavo.omnisign.ades.policy.AdESPolicy
import cz.pizavo.omnisign.data.trust.certFingerprint
import cz.pizavo.omnisign.data.util.extractCertificateDetails
import cz.pizavo.omnisign.data.util.readableDistinguishedName
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.signature.CertificateChainLink
import cz.pizavo.omnisign.domain.model.signature.CertificateDetailSection
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.signature.CertificateTrustSource
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import eu.europa.esig.dss.detailedreport.DetailedReport
import eu.europa.esig.dss.diagnostic.CertificateWrapper
import eu.europa.esig.dss.diagnostic.DiagnosticData
import eu.europa.esig.dss.diagnostic.TimestampWrapper
import eu.europa.esig.dss.enumerations.Indication
import eu.europa.esig.dss.enumerations.SignatureQualification
import eu.europa.esig.dss.enumerations.SubIndication
import eu.europa.esig.dss.enumerations.TokenExtractionStrategy
import eu.europa.esig.dss.enumerations.TimestampQualification
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.simplereport.SimpleReport
import eu.europa.esig.dss.validation.SignedDocumentValidator
import eu.europa.esig.dss.validation.reports.Reports
import java.io.File

/**
 * JVM implementation of [ValidationRepository] using the EU DSS library.
 *
 * Builds a certificate verifier with online CRL/OCSP sources, AIA support, optional EU LOTL and
 * custom trusted lists, and the directly-trusted certificates resolved from the [TrustStore] for
 * the active scope — all driven by the [ResolvedConfig] supplied in [ValidationParameters].
 *
 * Per-reference trust types are enforced as a post-validation downgrade: a signature or timestamp
 * DSS accepts is flagged [SignatureValidationResult.policyUntrusted] /
 * [TimestampValidationResult.policyUntrusted] when its terminating store anchor is trusted only for
 * the other role (see [isDowngradedByPolicy]).
 */
class DssValidationRepository(
	private val dssServiceFactory: DssServiceFactory,
	private val trustStore: TrustStore,
) : ValidationRepository {
	
	private val adeSPolicy = AdESPolicy()
	
	override suspend fun validateDocument(parameters: ValidationParameters): OperationResult<ValidationReport> {
		return Either.catch {
			val document = InMemoryDocument(parameters.inputBytes, parameters.inputName)

			val statusAlert = CollectingStatusAlert()
			val directAnchors = trustStore.resolve(TrustScope.of(parameters.resolvedConfig?.profileName))
				.getOrElse { emptyList() }
			val (cv, tlWarnings) = dssServiceFactory.buildValidationCertificateVerifier(
				parameters.resolvedConfig, directAnchors
			) { statusAlert }
			val validator = SignedDocumentValidator.fromDocument(document)
				.apply {
					setCertificateVerifier(cv)
					setTokenExtractionStrategy(TokenExtractionStrategy.EXTRACT_CERTIFICATES_ONLY)
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

			val profileName = parameters.resolvedConfig?.profileName
			val globalTrustedFingerprints = trustStore.list(TrustScope.Global)
				.getOrElse { emptyList() }.map { it.fingerprint }.toSet()
			val profileTrustedFingerprints = profileName
				?.let { name -> trustStore.list(TrustScope.Profile(name)).getOrElse { emptyList() }.map { it.fingerprint }.toSet() }
				?: emptySet()
			val trustSourcesOf: (CertificateWrapper) -> List<CertificateTrustSource> = { cert ->
				trustSourcesFor(cert, globalTrustedFingerprints, profileTrustedFingerprints, profileName)
			}

			val report = convertReports(reports, parameters.inputName, anchorTypeByDssId(directAnchors), trustSourcesOf)
			val annotatedSignatures = report.signatures.map { sig ->
				annotateDisabledAlgorithms(sig, disabledHash, disabledEncryption)
			}
			report.copy(
				signatures = annotatedSignatures,
				tlWarnings = tlWarnings + verifierWarnings,
				rawReports = extractRawReports(reports, parameters.rawReportFormats),
			)
		}.mapLeft { exception ->
			ValidationError.validationFailed(
				details = exception.message,
				cause = exception,
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
	 *
	 * The overall result is VALID only when every signature passed *and* none was downgraded by the
	 * per-reference trust policy ([SignatureValidationResult.policyUntrusted]).
	 *
	 * @param anchorTypes DSS certificate id → per-reference trust type for the store-managed anchors,
	 *   used to apply the post-validation downgrade.
	 */
	private fun convertReports(
		reports: Reports,
		documentName: String,
		anchorTypes: Map<String, TrustedCertificateType>,
		trustSourcesOf: (CertificateWrapper) -> List<CertificateTrustSource>,
	): ValidationReport {
		val simpleReport = reports.simpleReport
		val detailedReport = reports.detailedReport
		val diagnosticData = reports.diagnosticData
		
		val allTimestampResults = diagnosticData.getTimestampList().associate { tsw ->
			tsw.id to convertTimestamp(tsw, simpleReport, detailedReport, anchorTypes, trustSourcesOf)
		}

		val sealedRevocationIds = timestampSealedRevocationIds(diagnosticData, allTimestampResults)

		val signatureTimestampIds = mutableSetOf<String>()
		
		val signatures = simpleReport.signatureIdList.map { sigId ->
			val sigWrapper = diagnosticData.getSignatureById(sigId)
			val sigTsIds = sigWrapper?.timestampList
				?.filter { it.type == eu.europa.esig.dss.enumerations.TimestampType.SIGNATURE_TIMESTAMP }
				?.map { it.id }
				?: emptyList()
			signatureTimestampIds.addAll(sigTsIds)
			
			val sigTimestamps = sigTsIds.mapNotNull { tsId -> allTimestampResults[tsId] }
			
			convertSignature(simpleReport, diagnosticData, sigId, anchorTypes, sealedRevocationIds, trustSourcesOf).copy(
				timestamps = sigTimestamps
			)
		}
		
		val documentTimestamps = allTimestampResults
			.filterKeys { it !in signatureTimestampIds }
			.values.toList()
		
		val overallResult = when {
			signatures.all { it.indication == ValidationIndication.TOTAL_PASSED } &&
				signatures.none { it.policyUntrusted } -> ValidationResult.VALID
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
		detailedReport: DetailedReport,
		anchorTypes: Map<String, TrustedCertificateType>,
		trustSourcesOf: (CertificateWrapper) -> List<CertificateTrustSource>,
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
		
		val tsaSubjectDN = tsw.signingCertificate?.getCertificateDN()?.let(::readableDistinguishedName)
		val policyUntrusted = isDowngradedByPolicy(
			managedAnchorTypes(tsw.certificateChain, anchorTypes),
			TrustedCertificateType.TSA,
		)

		return TimestampValidationResult(
			timestampId = id,
			type = tsw.type?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unknown",
			indication = indication,
			subIndication = subIndication,
			productionTime = tsw.productionTime?.toKotlinInstant() ?: kotlin.time.Instant.fromEpochSeconds(0),
			qualification = qualification,
			tsaSubjectDN = tsaSubjectDN,
			euLotlBacked = isEuLotlBacked(tsw.certificateChain),
			errors = if (policyUntrusted) errors + TIMESTAMP_POLICY_MESSAGE else errors,
			warnings = warnings,
			infos = infos,
			policyUntrusted = policyUntrusted,
			chain = buildCertificateChain(tsw.certificateChain, trustSourcesOf),
		)
	}
	
	/**
	 * Convert a single DSS signature entry into a [SignatureValidationResult],
	 * pulling rich certificate details from the [DiagnosticData].
	 *
	 * @param sealedRevocationIds Ids of revocation tokens protected by a document/archive timestamp
	 *   (see [timestampSealedRevocationIds]), used to mark the signing certificate's revocation
	 *   evidence as timestamp-sealed.
	 */
	private fun convertSignature(
		simpleReport: SimpleReport,
		diagnosticData: DiagnosticData,
		signatureId: String,
		anchorTypes: Map<String, TrustedCertificateType>,
		sealedRevocationIds: Set<String>,
		trustSourcesOf: (CertificateWrapper) -> List<CertificateTrustSource>,
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
		val dssQualification = simpleReport.getSignatureQualification(signatureId)
		val trustTier = dssQualification?.toTrustTier() ?: SignatureTrustTier.NOT_QUALIFIED
		val qualificationInfos = simpleReport.getQualificationInfo(signatureId).map { it.value } +
			listOfNotNull(trustTier.qscdResidenceInfo())
		
		val signedBy = simpleReport.getSignedBy(signatureId) ?: "Unknown"
		val signatureLevel = simpleReport.getSignatureFormat(signatureId)?.toString() ?: "Unknown"
		val signatureTime = simpleReport.getBestSignatureTime(signatureId)?.toKotlinInstant()
			?: kotlin.time.Instant.fromEpochSeconds(0)
		
		val sigWrapper = diagnosticData.getSignatureById(signatureId)
		val signingCert = sigWrapper?.signingCertificate
		val revocations = signingCert?.let { extractRevocations(it, sealedRevocationIds) } ?: emptyList()
		val policyUntrusted = isDowngradedByPolicy(
			managedAnchorTypes(sigWrapper?.certificateChain ?: emptyList(), anchorTypes),
			TrustedCertificateType.CA,
		)
		
		val sha256Fingerprint = signingCert?.digestAlgoAndValue?.digestValue?.let { bytes ->
			bytes.joinToString(":") { "%02X".format(it) }
		}
		
		val euLotlBacked = isEuLotlBacked(sigWrapper?.certificateChain)
		
		val certificate = CertificateInfo(
			subjectDN = signingCert?.getCertificateDN()?.let(::readableDistinguishedName) ?: signedBy,
			issuerDN = signingCert?.getCertificateIssuerDN()?.let(::readableDistinguishedName) ?: "Unknown",
			serialNumber = signingCert?.serialNumber ?: "Unknown",
			validFrom = signingCert?.notBefore?.toKotlinInstant() ?: kotlin.time.Instant.fromEpochSeconds(0),
			validTo = signingCert?.notAfter?.toKotlinInstant() ?: kotlin.time.Instant.fromEpochSeconds(0),
			keyUsages = signingCert?.keyUsages?.map { it.name } ?: emptyList(),
			isQualified = trustTier != SignatureTrustTier.NOT_QUALIFIED,
			publicKeyAlgorithm = signingCert?.encryptionAlgorithm?.name,
			sha256Fingerprint = sha256Fingerprint,
			chain = buildCertificateChain(sigWrapper?.certificateChain, trustSourcesOf),
		)
		
		val signatureQualification = dssQualification
			?.takeIf { it != SignatureQualification.NA }
			?.readable
		
		return SignatureValidationResult(
			signatureId = signatureId,
			indication = indication,
			subIndication = simpleReport.getSubIndication(signatureId)?.toString(),
			errors = if (policyUntrusted) errors + SIGNATURE_POLICY_MESSAGE else errors,
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
			euLotlBacked = euLotlBacked,
			hashAlgorithm = sigWrapper?.digestAlgorithm?.name,
			encryptionAlgorithm = sigWrapper?.encryptionAlgorithm?.name,
			policyUntrusted = policyUntrusted,
			revocations = revocations,
		)
	}

	/**
	 * Ids of revocation tokens covered by a document- or archive-timestamp that did not fail
	 * validation. Such a timestamp seals the embedded revocation data, giving it a
	 * proof-of-existence — the basis for the "no remote check needed, provably contemporaneous"
	 * statement on a PAdES-BASELINE-LTA (or any archive-timestamped) signature.
	 */
	private fun timestampSealedRevocationIds(
		diagnosticData: DiagnosticData,
		timestampResults: Map<String, TimestampValidationResult>,
	): Set<String> =
		diagnosticData.getTimestampList()
			.filter {
				it.type == eu.europa.esig.dss.enumerations.TimestampType.DOCUMENT_TIMESTAMP ||
					it.type == eu.europa.esig.dss.enumerations.TimestampType.ARCHIVE_TIMESTAMP
			}
			.filter { timestampResults[it.id]?.indication != ValidationIndication.TOTAL_FAILED }
			.flatMap { it.timestampedRevocations }
			.map { it.id }
			.toSet()

	/**
	 * Build the signing certificate's full chain (leaf-first, up to the trust anchor) as
	 * [CertificateChainLink]s, parsing every certificate's DER into a complete field dump. A
	 * certificate whose binaries DSS did not extract is skipped. Drives the full-certificate dialog.
	 */
	private fun buildCertificateChain(
		chain: List<CertificateWrapper>?,
		trustSourcesOf: (CertificateWrapper) -> List<CertificateTrustSource>,
	): List<CertificateChainLink> =
		chain.orEmpty().mapNotNull { cert ->
			val der = cert.binaries ?: return@mapNotNull null
			val details = extractCertificateDetails(der)
			CertificateChainLink(
				commonName = subjectCommonName(details),
				subjectDN = readableDistinguishedName(cert.getCertificateDN()),
				selfSigned = cert.getCertificateDN() == cert.getCertificateIssuerDN(),
				trustedVia = trustSourcesOf(cert),
				details = details,
				der = der,
			)
		}

	/**
	 * The trust sources that vouch for [cert] under the validation's environment — empty unless
	 * [cert] is itself a trust anchor ([CertificateWrapper.isTrusted]). This gate matters because
	 * DSS's `trustServices` reflects a certificate's chain relationship to a trusted-list CA and is
	 * populated on the leaf too; only the anchor is actually trusted, so a leaf covered by a
	 * TL-listed CA must not be marked.
	 *
	 * For an anchor, store membership is matched against our own per-scope fingerprints (so global
	 * and profile are attributed distinctly); a trusted certificate not in our store is anchored by
	 * a trusted list, named EU LOTL when it is backed by it.
	 */
	private fun trustSourcesFor(
		cert: CertificateWrapper,
		globalTrustedFingerprints: Set<String>,
		profileTrustedFingerprints: Set<String>,
		profileName: String?,
	): List<CertificateTrustSource> {
		if (!cert.isTrusted) return emptyList()
		val fingerprint = cert.binaries?.let { certFingerprint(it) }
		val storeSources = buildList {
			if (fingerprint != null) {
				if (fingerprint in globalTrustedFingerprints) add(CertificateTrustSource.GlobalStore)
				if (profileName != null && fingerprint in profileTrustedFingerprints) {
					add(CertificateTrustSource.ProfileStore(profileName))
				}
			}
		}
		return storeSources.ifEmpty {
			listOf(CertificateTrustSource.TrustedList(if (isEuLotlBackedCert(cert)) "EU LOTL" else "Trusted list"))
		}
	}

	/** Whether [cert]'s trust services place it on the EU LOTL (or a national list that is a member). */
	private fun isEuLotlBackedCert(cert: CertificateWrapper): Boolean =
		cert.trustServices.any { ts ->
			ts.listOfTrustedLists?.url == DssServiceFactory.EU_LOTL_URL ||
				ts.trustedList?.let {
					it.url == DssServiceFactory.EU_LOTL_URL || it.parent?.url == DssServiceFactory.EU_LOTL_URL
				} == true
		}

	/**
	 * Pull the subject common name out of an already-parsed [details] dump for a concise chain-row
	 * label, or `null` when the subject carries none. Reads the "Subject" section's CN field produced
	 * by [extractCertificateDetails]; on absence returns `null` so the caller shows the full DN.
	 */
	private fun subjectCommonName(details: List<CertificateDetailSection>): String? =
		details.firstOrNull { it.title == "Subject" }
			?.fields?.firstOrNull { it.label.endsWith("(CN)") }
			?.value

	/**
	 * Map *every* revocation token DSS holds for the signing certificate into [RevocationInfo],
	 * newest first, so the caller can present all of them — typically an embedded token sealed at
	 * signing time and a fresh one fetched online during validation — without choosing between them.
	 * [sealedRevocationIds] marks tokens a document/archive timestamp protects (see
	 * [timestampSealedRevocationIds]).
	 */
	private fun extractRevocations(
		signingCert: CertificateWrapper,
		sealedRevocationIds: Set<String>,
	): List<RevocationInfo> =
		signingCert.certificateRevocationData
			.sortedByDescending { it.productionDate?.time ?: Long.MIN_VALUE }
			.map { revocation ->
				RevocationInfo(
					method = revocation.revocationType?.name ?: "Unknown",
					status = revocation.status?.name ?: "UNKNOWN",
					revoked = revocation.isRevoked,
					embedded = revocation.isInternalRevocationOrigin,
					sealedByTimestamp = revocation.id in sealedRevocationIds,
					origin = revocation.origin?.name ?: "Unknown",
					sourceUrl = revocation.sourceAddress?.takeIf { it.isNotBlank() },
					producedAt = revocation.productionDate?.toKotlinInstant(),
					thisUpdate = revocation.thisUpdate?.toKotlinInstant(),
					nextUpdate = revocation.nextUpdate?.toKotlinInstant(),
					revocationDate = revocation.revocationDate?.toKotlinInstant(),
					reason = revocation.reason?.name,
				)
			}
	
	/**
	 * Whether a certificate chain's eIDAS trust anchor is published on the EU LOTL (or a national
	 * trusted list that is a member of it), as opposed to a user-added custom trusted list. Applies
	 * to both signing certificates and TSA certificates.
	 *
	 * Walks [certificateChain] and checks the trust services that govern its trust anchor: it is
	 * EU-LOTL-backed when any such service's trusted list (or the list of trusted lists it belongs
	 * to) is the EU LOTL ([DssServiceFactory.EU_LOTL_URL]).
	 */
	private fun isEuLotlBacked(certificateChain: List<CertificateWrapper>?): Boolean =
		certificateChain?.any { isEuLotlBackedCert(it) } ?: false

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
	 * Marshal the raw DSS report XML strings the caller actually requested via
	 * [ValidationParameters.rawReportFormats] so they can be carried on the domain
	 * [ValidationReport] for later export.
	 *
	 * Each `reports.xml*` getter triggers a JAXB marshalling pass (the diagnostic-data
	 * report in particular can be large), so formats absent from [formats] are never
	 * marshaled. An empty [formats] set — the default for the CLI and server, which do
	 * not expose raw-report export — skips marshalling entirely.
	 */
	private fun extractRawReports(
		reports: Reports,
		formats: Set<RawReportFormat>,
	): Map<RawReportFormat, String> {
		if (formats.isEmpty()) return emptyMap()
		return buildMap {
			if (RawReportFormat.XML_DETAILED in formats) {
				reports.xmlDetailedReport?.let { put(RawReportFormat.XML_DETAILED, it) }
			}
			if (RawReportFormat.XML_SIMPLE in formats) {
				reports.xmlSimpleReport?.let { put(RawReportFormat.XML_SIMPLE, it) }
			}
			if (RawReportFormat.XML_DIAGNOSTIC in formats) {
				reports.xmlDiagnosticData?.let { put(RawReportFormat.XML_DIAGNOSTIC, it) }
			}
			if (RawReportFormat.XML_ETSI in formats) {
				reports.xmlValidationReport?.let { put(RawReportFormat.XML_ETSI, it) }
			}
		}
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

	/**
	 * Map each resolved anchor's DSS certificate id to its per-reference trust type, so a
	 * component's terminating trusted anchor can be matched back to the policy it carries.
	 */
	private fun anchorTypeByDssId(
		anchors: List<ResolvedTrustAnchor>,
	): Map<String, TrustedCertificateType> = anchors.associate { anchor ->
		val x509 = java.security.cert.CertificateFactory.getInstance("X.509")
			.generateCertificate(anchor.der.inputStream()) as java.security.cert.X509Certificate
		CertificateToken(x509).getDSSIdAsString() to anchor.type
	}

	/**
	 * The per-reference types of the store-managed anchors that terminate [chain] - empty when
	 * trust came from a trusted list or an anchor the store does not manage.
	 */
	private fun managedAnchorTypes(
		chain: List<CertificateWrapper>,
		anchorTypes: Map<String, TrustedCertificateType>,
	): List<TrustedCertificateType> =
		chain.filter { it.isTrusted }.mapNotNull { anchorTypes[it.id] }

	private companion object {
		const val SIGNATURE_POLICY_MESSAGE =
			"Signature distrusted by policy: its trust anchor is trusted for timestamping only, not for signing"
		const val TIMESTAMP_POLICY_MESSAGE =
			"Timestamp distrusted by policy: its trust anchor is trusted as a certificate authority only, not for timestamping"
	}
}
