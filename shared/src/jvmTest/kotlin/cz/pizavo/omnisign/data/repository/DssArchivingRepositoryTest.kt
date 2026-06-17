package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.ades.policy.AdESPolicy
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import eu.europa.esig.dss.diagnostic.TimestampWrapper
import eu.europa.esig.dss.diagnostic.jaxb.XmlBasicSignature
import eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate
import eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher
import eu.europa.esig.dss.diagnostic.jaxb.XmlSigningCertificate
import eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp
import eu.europa.esig.dss.diagnostic.jaxb.XmlTimestampedObject
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.DigestMatcherType
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm
import eu.europa.esig.dss.enumerations.TimestampType
import eu.europa.esig.dss.enumerations.TimestampedObjectType
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.Date
import kotlin.time.Instant

/**
 * Verifies [DssArchivingRepository] error handling and lightweight PDF inspection
 * using Arrow [arrow.core.Either] matchers.
 */
class DssArchivingRepositoryTest : FunSpec({
	
	val tmpDir = tempdir()
	
	val configRepository: ConfigRepository = mockk()
	val dssServiceFactory: DssServiceFactory = mockk(relaxed = true)
	
	val repository = DssArchivingRepository(configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(), RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()), mockk<RenewalCheckCache>(relaxed = true))

	val cryptographicSuite = AdESPolicy().cryptographicSuite()
	
	fun configWithoutTsa() = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA
		)
	)
	
	/**
	 * Create a minimal valid PDF file with no signatures.
	 */
	fun createPlainPdf(name: String): File {
		val file = File(tmpDir, name)
		PDDocument().use { doc ->
			doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
			doc.save(file)
		}
		return file
	}
	
	/**
	 * Create a valid PDF whose catalog contains a `/DSS` dictionary entry,
	 * simulating a PAdES-BASELINE-LT document without doing real signing.
	 */
	fun createPdfWithDssDictionary(name: String): File {
		val file = File(tmpDir, name)
		PDDocument().use { doc ->
			doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
			doc.documentCatalog.cosObject.setItem(
				COSName.getPDFName("DSS"),
				COSDictionary()
			)
			doc.save(file)
		}
		return file
	}
	
	test("extendDocument returns ExtensionFailed when no TSA is configured") {
		coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()

		repository.extendDocument(
			ArchivingParameters(
				inputBytes = ByteArray(0),
				inputName = "signed.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_T
			)
		).shouldBeLeft().shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}

	test("extendDocument returns ExtensionFailed when target level is B-B") {
		coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()

		repository.extendDocument(
			ArchivingParameters(
				inputBytes = ByteArray(0),
				inputName = "signed2.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_B
			)
		).shouldBeLeft().shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
	
	test("needsArchivalRenewal returns ExtensionFailed for a non-existent file") {
		repository.needsArchivalRenewal("/nonexistent/doc.pdf")
			.shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
	
	test("getDocumentTimestampInfo reports no timestamps and no LT data for a plain PDF") {
		val pdf = createPlainPdf("plain.pdf")
		val info = repository.getDocumentTimestampInfo(pdf.readBytes()).shouldBeRight()
		info.hasDocumentTimestamp.shouldBeFalse()
		info.containsLtData.shouldBeFalse()
	}

	test("getDocumentTimestampInfo detects LT data when DSS dictionary is present") {
		val pdf = createPdfWithDssDictionary("with-dss.pdf")
		val info = repository.getDocumentTimestampInfo(pdf.readBytes()).shouldBeRight()
		info.hasDocumentTimestamp.shouldBeFalse()
		info.containsLtData.shouldBeTrue()
	}

	test("getDocumentTimestampInfo returns error for corrupt bytes") {
		repository.getDocumentTimestampInfo("not a PDF".toByteArray())
			.shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}

	val renewalThreshold = Instant.parse("2026-09-11T00:00:00Z")
	val agingCert = Instant.parse("2026-09-01T00:00:00Z") // expires before the threshold → inside the window
	val freshCert = Instant.parse("2027-09-01T00:00:00Z") // expires well after the threshold → safe

	/**
	 * Build a DSS [TimestampWrapper] over a hand-assembled [XmlTimestamp] for renewal-decision tests:
	 * [type] sets the timestamp category, [expiry] the signing certificate's `notAfter` (null = no
	 * resolvable signing certificate), and [covers] the ids of the timestamps this one seals.
	 */
	fun timestamp(
		id: String,
		type: TimestampType,
		expiry: Instant?,
		covers: List<String> = emptyList(),
		signatureDigest: DigestAlgorithm? = null,
		signatureEncryption: EncryptionAlgorithm? = null,
		signatureKeyLength: String? = null,
		messageImprintDigest: DigestAlgorithm? = null,
	): TimestampWrapper = TimestampWrapper(
		XmlTimestamp().apply {
			this.id = id
			this.type = type
			expiry?.let {
				signingCertificate = XmlSigningCertificate().apply {
					certificate = XmlCertificate().apply {
						this.id = "$id-cert"
						notAfter = Date(it.toEpochMilliseconds())
					}
				}
			}
			if (signatureDigest != null || signatureEncryption != null || signatureKeyLength != null) {
				basicSignature = XmlBasicSignature().apply {
					digestAlgoUsedToSignThisToken = signatureDigest
					encryptionAlgoUsedToSignThisToken = signatureEncryption
					keyLengthUsedToSignThisToken = signatureKeyLength
				}
			}
			messageImprintDigest?.let { imprint ->
				digestMatchers.add(
					XmlDigestMatcher().apply {
						this.type = DigestMatcherType.MESSAGE_IMPRINT
						this.digestMethod = imprint
					}
				)
			}
			timestampedObjects = covers.map { coveredId ->
				XmlTimestampedObject().apply {
					category = TimestampedObjectType.TIMESTAMP
					token = XmlTimestamp().apply { this.id = coveredId }
				}
			}
		}
	)

	test("needsRenewal skips a stale signature timestamp sealed by a fresh document timestamp") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = agingCert),
			timestamp("doc", TimestampType.DOCUMENT_TIMESTAMP, expiry = freshCert, covers = listOf("sig")),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NOT_NEEDED
	}

	test("needsRenewal triggers when a B-LT signature timestamp is aging and unsealed") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = agingCert),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal triggers when the outermost document timestamp is aging") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert),
			timestamp("doc", TimestampType.DOCUMENT_TIMESTAMP, expiry = agingCert, covers = listOf("sig")),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal triggers for a signature added after archival that the seal does not cover") {
		val timestamps = listOf(
			timestamp("sig1", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert),
			timestamp("doc", TimestampType.DOCUMENT_TIMESTAMP, expiry = freshCert, covers = listOf("sig1")),
			timestamp("sig2", TimestampType.SIGNATURE_TIMESTAMP, expiry = agingCert),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal ignores an aged inner timestamp covered by a fresh outer timestamp in the chain") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = agingCert),
			timestamp("doc1", TimestampType.DOCUMENT_TIMESTAMP, expiry = agingCert, covers = listOf("sig")),
			timestamp("doc2", TimestampType.DOCUMENT_TIMESTAMP, expiry = freshCert, covers = listOf("sig", "doc1")),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NOT_NEEDED
	}

	test("needsRenewal returns false for a document with no timestamps") {
		repository.needsRenewal(emptyList(), renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NOT_NEEDED
	}

	test("needsRenewal reports undeterminable when the only relevant timestamp has an unresolvable signing cert") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = null),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.UNDETERMINABLE
	}

	test("needsRenewal prefers a clear renewal over an unresolvable signing cert") {
		val timestamps = listOf(
			timestamp("sig-unresolvable", TimestampType.SIGNATURE_TIMESTAMP, expiry = null),
			timestamp("sig-aging", TimestampType.SIGNATURE_TIMESTAMP, expiry = agingCert),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal ignores an unresolvable signing cert on a timestamp sealed by a fresh document timestamp") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = null),
			timestamp("doc", TimestampType.DOCUMENT_TIMESTAMP, expiry = freshCert, covers = listOf("sig")),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NOT_NEEDED
	}

	test("needsRenewal reports undeterminable when a safe timestamp coexists with an unresolvable uncovered one") {
		val timestamps = listOf(
			timestamp("sig-fresh", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert),
			timestamp("sig-unresolvable", TimestampType.SIGNATURE_TIMESTAMP, expiry = null),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.UNDETERMINABLE
	}

	test("needsRenewal triggers when a timestamp's message-imprint digest is obsolete") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert, messageImprintDigest = DigestAlgorithm.SHA1),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal triggers when a timestamp's signature digest algorithm is obsolete") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert, signatureDigest = DigestAlgorithm.SHA1),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal triggers when a timestamp's signature key size has aged out") {
		val timestamps = listOf(
			timestamp(
				"sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert,
				signatureEncryption = EncryptionAlgorithm.RSA, signatureKeyLength = "1024",
			),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal does not trigger for a current strong digest") {
		val timestamps = listOf(
			timestamp(
				"sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert,
				messageImprintDigest = DigestAlgorithm.SHA256, signatureDigest = DigestAlgorithm.SHA256,
			),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NOT_NEEDED
	}

	test("needsRenewal prefers algorithm renewal over an unresolvable signing cert") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = null, messageImprintDigest = DigestAlgorithm.SHA1),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NEEDED
	}

	test("needsRenewal ignores an obsolete algorithm on a timestamp sealed by a fresh document timestamp") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert, messageImprintDigest = DigestAlgorithm.SHA1),
			timestamp(
				"doc", TimestampType.DOCUMENT_TIMESTAMP, expiry = freshCert, covers = listOf("sig"),
				messageImprintDigest = DigestAlgorithm.SHA256,
			),
		)
		repository.needsRenewal(timestamps, renewalThreshold, cryptographicSuite) shouldBe RenewalDecision.NOT_NEEDED
	}

	test("earliestRenewalAt returns the soonest signing-cert expiry among relevant timestamps") {
		val timestamps = listOf(
			timestamp("sig1", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert),
			timestamp("sig2", TimestampType.SIGNATURE_TIMESTAMP, expiry = agingCert),
		)
		repository.earliestRenewalAt(timestamps, cryptographicSuite) shouldBe agingCert
	}

	test("earliestRenewalAt ignores timestamps sealed by a fresh document timestamp") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = agingCert),
			timestamp("doc", TimestampType.DOCUMENT_TIMESTAMP, expiry = freshCert, covers = listOf("sig")),
		)
		repository.earliestRenewalAt(timestamps, cryptographicSuite) shouldBe freshCert
	}

	test("earliestRenewalAt reflects an algorithm expiry sooner than the signing cert") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = freshCert, messageImprintDigest = DigestAlgorithm.SHA1),
		)
		val due = repository.earliestRenewalAt(timestamps, cryptographicSuite)
		(due!! < freshCert).shouldBeTrue()
	}

	test("earliestRenewalAt returns null when a relevant timestamp's signing cert is unresolvable") {
		val timestamps = listOf(
			timestamp("sig", TimestampType.SIGNATURE_TIMESTAMP, expiry = null),
		)
		repository.earliestRenewalAt(timestamps, cryptographicSuite) shouldBe null
	}

	test("earliestRenewalAt returns null when no timestamp drives renewal") {
		repository.earliestRenewalAt(emptyList(), cryptographicSuite) shouldBe null
	}
})

