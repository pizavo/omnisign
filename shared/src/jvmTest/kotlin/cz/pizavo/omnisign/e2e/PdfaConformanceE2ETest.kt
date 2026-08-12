package cz.pizavo.omnisign.e2e

import arrow.core.right
import cz.pizavo.omnisign.data.repository.*
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.service.pkcs11CertAlias
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.pdfa.PDFAStructureValidator
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.DSSUtils
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdfwriter.compress.CompressParameters
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.*

/**
 * End-to-end guard that signing a PDF/A document leaves it PDF/A-conformant.
 *
 * The application relies on DSS's `dss-pdfa` module to *report* conformance during validation, but
 * nothing previously checked that its own signing path preserves it. It did not: the `/Contents`
 * reservation chosen per PAdES level used to exceed the 32,767-byte string limit that PDF/A-1/2/3
 * inherit from ISO 32000-1 Annex C (ISO 19005-3 clause 6.1.13, test 3), which made every B-LT and
 * B-LTA signature turn a conformant input into a non-conformant output. Because PDF/A measures the
 * declared string length and not its meaningful content, the violation consisted purely of the zero
 * padding — see [DssSigningRepository]'s `contentSizeForLevel`.
 *
 * The fixture is built here rather than committed so that no ICC profile binary enters the
 * repository: the sRGB profile comes from the JDK's own colour management, and veraPDF confirms the
 * unsigned fixture is conformant before any test signs it.
 */
class PdfaConformanceE2ETest : FunSpec({
	
	val tmp = tempdir()
	val pass = "test1234"
	
	fun fixture(name: String): File {
		val bytes = PdfaConformanceE2ETest::class.java.getResourceAsStream("/e2e/$name")?.readBytes()
			?: error("Missing test fixture /e2e/$name")
		return File(tmp, name).also { it.writeBytes(bytes) }
	}
	
	val signerP12 = fixture("signer.test.p12")
	val tsa = LocalTestTsa.start(fixture("tsa.test.p12").readBytes(), pass)
	afterSpec { tsa.close() }
	
	val signerKeyStore = KeyStore.getInstance("PKCS12").apply {
		signerP12.inputStream().use { load(it, pass.toCharArray()) }
	}
	val signerCert = signerKeyStore.getCertificate(signerKeyStore.aliases().nextElement()) as X509Certificate
	
	val fileToken = TokenInfo(
		id = "pdfa-e2e", name = "signer.test.p12", type = TokenType.FILE,
		path = signerP12.absolutePath, requiresPin = false,
	)
	val certEntry = CertificateEntry(
		alias = pkcs11CertAlias(signerCert, fileToken),
		subjectDN = signerCert.subjectX500Principal.toString(),
		issuerDN = signerCert.issuerX500Principal.toString(),
		serialNumber = signerCert.serialNumber.toString(),
		validFrom = signerCert.notBefore.toKotlinInstant(),
		validTo = signerCert.notAfter.toKotlinInstant(),
		keyUsages = emptyList(),
		tokenInfo = fileToken,
	)
	
	val caKeyStore = KeyStore.getInstance("PKCS12").apply {
		fixture("ca.test.p12").inputStream().use { load(it, pass.toCharArray()) }
	}
	val caAlias = caKeyStore.aliases().nextElement()
	val caKey = caKeyStore.getKey(caAlias, pass.toCharArray()) as PrivateKey
	val caCert = caKeyStore.getCertificate(caAlias) as X509Certificate
	
	val crl: X509CRL = run {
		val now = Date()
		val builder = X509v2CRLBuilder(JcaX509CertificateHolder(caCert).subject, Date(now.time - 86_400_000L))
		builder.setNextUpdate(Date(now.time + 86_400_000L * 3650))
		val contentSigner = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(caKey)
		JcaX509CRLConverter().setProvider("BC").getCRL(builder.build(contentSigner))
	}
	
	fun trustAndRevocationVerifier(): CommonCertificateVerifier =
		CommonCertificateVerifier().apply {
			setTrustedCertSources(
				CommonTrustedCertificateSource().apply { addCertificate(DSSUtils.loadCertificate(caCert.encoded)) },
			)
			crlSource = ExternalResourcesCRLSource(InMemoryDocument(crl.encoded))
			isCheckRevocationForUntrustedChains = false
			alertOnMissingRevocationData = null
			alertOnUncoveredPOE = null
			alertOnInvalidTimestamp = null
			alertOnNoRevocationAfterBestSignatureTime = null
			alertOnRevokedCertificate = null
		}
	
	fun realSigningToken(): SigningToken {
		val token = Pkcs12SignatureToken(signerP12, KeyStore.PasswordProtection(pass.toCharArray()))
		return object : SigningToken {
			override fun getDssToken(): Any = token
			override fun close() = token.close()
		}
	}
	
	val tokenService = mockk<TokenService>()
	coEvery { tokenService.discoverTokens() } returns listOf(fileToken).right()
	coEvery { tokenService.probeTokenPresent(any()) } returns true
	coEvery { tokenService.loadCertificatesSilent(any(), any()) } returns listOf(certEntry).right()
	coEvery { tokenService.getSigningToken(any(), any()) } answers { realSigningToken().right() }
	coEvery { tokenService.openSigningToken(any(), any()) } answers { realSigningToken().right() }
	
	val configRepository = mockk<ConfigRepository>()
	coEvery { configRepository.getCurrentConfig() } returns AppConfig(global = GlobalConfig())
	
	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
	every { dssServiceFactory.buildPdfObjectFactory() } returns PdfBoxNativeObjectFactory()
	every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(trustAndRevocationVerifier())
	}
	every { dssServiceFactory.buildExtendCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(
			trustAndRevocationVerifier().apply {
				alertOnMissingRevocationData = arg<() -> StatusAlert>(2)()
				alertOnNoRevocationAfterBestSignatureTime = arg<() -> StatusAlert>(2)()
			},
		)
	}
	every { dssServiceFactory.buildTspSource(any()) } returns
			OnlineTSPSource(tsa.url).apply { setDataLoader(TimestampDataLoader()) }
	
	val signingRepository = DssSigningRepository(
		tokenService,
		configRepository,
		mockk<CredentialStore>(relaxed = true),
		dssServiceFactory,
		AlgorithmExpirationChecker(),
		DssWarningSanitizer(),
		TspErrorDetector(),
		FileTrustStore(tempdir().toPath()),
		DocumentInputErrorDetector(),
		Pkcs11SessionCache(),
		SignatureSpaceErrorDetector(),
	)
	val archivingRepository = DssArchivingRepository(
		configRepository, dssServiceFactory, DssWarningSanitizer(), TspErrorDetector(),
		RevocationErrorDetector(), DocumentInputErrorDetector(), FileTrustStore(tempdir().toPath()),
		mockk<RenewalCheckCache>(relaxed = true), SignatureSpaceErrorDetector(),
	)
	
	/**
	 * XMP packet declaring the document as the given PDF/A [part] and [conformance] level.
	 *
	 * Parts 1 to 3 must keep their Dublin Core, PDF and XMP properties in step with the document
	 * information dictionary, so those are mirrored here. Part 4 dropped conformance levels (its
	 * variants are the unlettered base, E and F), requires `pdfaid:rev`, and forbids a document
	 * information dictionary, so it carries only the modification date.
	 */
	fun xmpPacket(part: Int, conformance: String?, title: String, producer: String, stamp: String): ByteArray =
		buildString {
			append("<?xpacket begin=\"").append((0xFEFF).toChar()).append("\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
			append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
			append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
			append("<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">\n")
			append("<pdfaid:part>").append(part).append("</pdfaid:part>\n")
			if (part == PDFA_PART_4) append("<pdfaid:rev>2020</pdfaid:rev>\n")
			conformance?.let { append("<pdfaid:conformance>").append(it).append("</pdfaid:conformance>\n") }
			append("</rdf:Description>\n")
			if (part != PDFA_PART_4) {
				append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
				append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(title)
				append("</rdf:li></rdf:Alt></dc:title>\n</rdf:Description>\n")
				append("<rdf:Description rdf:about=\"\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n")
				append("<pdf:Producer>").append(producer).append("</pdf:Producer>\n</rdf:Description>\n")
				append("<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n")
				append("<xmp:CreatorTool>").append(producer).append("</xmp:CreatorTool>\n")
				append("<xmp:CreateDate>").append(stamp).append("</xmp:CreateDate>\n")
				append("<xmp:ModifyDate>").append(stamp).append("</xmp:ModifyDate>\n</rdf:Description>\n")
			} else {
				append("<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n")
				append("<xmp:ModifyDate>").append(stamp).append("</xmp:ModifyDate>\n</rdf:Description>\n")
			}
			append("</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>")
		}.toByteArray(Charsets.UTF_8)
	
	/**
	 * Attach an embedded file, which is what makes a document PDF/A-4f rather than plain PDF/A-4.
	 */
	fun embedAttachment(doc: PDDocument) {
		val embedded = PDEmbeddedFile(doc, "attachment".byteInputStream()).apply {
			subtype = "text/plain"
			size = "attachment".length
			modDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
		}
		val spec = PDComplexFileSpecification().apply {
			file = "attachment.txt"
			fileUnicode = "attachment.txt"
			embeddedFile = embedded
			embeddedFileUnicode = embedded
			fileDescription = "fixture attachment"
			cosObject.setName(COSName.getPDFName("AFRelationship"), "Unspecified")
		}
		doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply {
			embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("attachment.txt" to spec) }
		}
		doc.documentCatalog.cosObject.setItem(
			COSName.getPDFName("AF"),
			COSArray().apply { add(spec.cosObject) },
		)
	}
	
	/**
	 * A minimal but genuinely conformant fixture for the given PDF/A [part] and [conformance] level:
	 * one empty page, an sRGB output intent taken from the JDK's own colour management so no ICC
	 * binary has to be committed, and a matching XMP packet.
	 *
	 * Three part-specific requirements are handled here, each of which veraPDF rejects the fixture
	 * for otherwise. Part 1 is built on PDF 1.4 and forbids cross-reference streams, so it is saved
	 * uncompressed to get a classic cross-reference table. Part 4 requires a PDF 2.0 header and
	 * forbids the document information dictionary unless the catalog has a `PieceInfo` entry.
	 * Conformance level F additionally requires an embedded file.
	 */
	fun pdfaFixture(part: Int, conformance: String?, version: Float): ByteArray {
		val title = "OmniSign PDF/A conformance fixture"
		val producer = "OmniSign test"
		val out = ByteArrayOutputStream()
		PDDocument().use { doc ->
			doc.version = version
			doc.document.version = version
			doc.addPage(PDPage(PDRectangle.A4))
			val stampCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
				clear()
				set(2026, Calendar.JANUARY, 1, 0, 0, 0)
			}
			if (part != PDFA_PART_4) {
				doc.documentInformation.apply {
					setTitle(title)
					setProducer(producer)
					creator = producer
					creationDate = stampCal
					modificationDate = stampCal
				}
			}
			val icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
			doc.documentCatalog.addOutputIntent(
				PDOutputIntent(doc, icc.inputStream()).apply {
					info = "sRGB IEC61966-2.1"
					outputCondition = "sRGB IEC61966-2.1"
					outputConditionIdentifier = "sRGB IEC61966-2.1"
					registryName = "http://www.color.org"
				},
			)
			doc.documentCatalog.metadata = PDMetadata(
				doc,
				xmpPacket(part, conformance, title, producer, "2026-01-01T00:00:00Z").inputStream(),
			)
			if (conformance == "F") embedAttachment(doc)
			if (part == PDFA_PART_4) doc.document.trailer.removeItem(COSName.getPDFName("Info"))
			if (version < PDF_XREF_STREAM_VERSION) {
				doc.save(out, CompressParameters.NO_COMPRESSION)
			} else {
				doc.save(out)
			}
		}
		return out.toByteArray()
	}
	
	/** The assignment's format, and the one the deep per-level sweep below uses. */
	fun pdfA3b(): ByteArray = pdfaFixture(part = 3, conformance = "B", version = 1.7f)
	
	/** veraPDF's verdict on [pdf], via the same `dss-pdfa` validator the application uses. */
	fun pdfaResult(pdf: ByteArray) = PDFAStructureValidator().validate(InMemoryDocument(pdf))
	
	/** The longest `/Contents` string in [pdf] — what PDF/A's implementation limit measures. */
	fun longestContentsString(pdf: ByteArray): Int =
		Loader.loadPDF(pdf).use { doc ->
			doc.signatureDictionaries.maxOfOrNull { it.contents.size } ?: 0
		}
	
	fun resolvedConfig(global: GlobalConfig): ResolvedConfig =
		ResolvedConfig.resolve(global = global, profile = null, operationOverrides = null).getOrNull()!!
	
	suspend fun sign(
		input: ByteArray,
		level: SignatureLevel,
		visible: VisibleSignatureParameters? = null,
	): ByteArray = signingRepository.signDocument(
		SigningParameters(
			inputBytes = input,
			inputName = "input.pdf",
			certificateAlias = certEntry.alias,
			signatureLevel = level,
			addTimestamp = level != SignatureLevel.PADES_BASELINE_B,
			visibleSignature = visible,
			resolvedConfig = resolvedConfig(
				GlobalConfig(
					defaultSignatureLevel = level,
					timestampServer = TimestampServerConfig(url = tsa.url),
				),
			),
		)
	).shouldBeRight().outputBytes
	
	test("the generated fixture really is PDF/A-3b before anything signs it") {
		val result = pdfaResult(pdfA3b())
		
		result.profileId shouldBe "PDF/A-3B"
		withClue(result.errorMessages) { result.isCompliant.shouldBeTrue() }
	}
	
	SignatureLevel.entries.forEach { level ->
		test("signing a PDF/A-3b document at $level keeps it PDF/A-3b conformant") {
			val signed = sign(pdfA3b(), level)
			
			val result = pdfaResult(signed)
			result.profileId shouldBe "PDF/A-3B"
			withClue(result.errorMessages) { result.isCompliant.shouldBeTrue() }
		}
		
		test("the /Contents reservation at $level stays within the PDF/A string limit") {
			longestContentsString(sign(pdfA3b(), level)) shouldBeLessThanOrEqual PDFA_MAX_STRING_LENGTH
		}
	}
	
	test("a visible signature keeps a PDF/A-3b document conformant") {
		val signed = sign(
			pdfA3b(),
			SignatureLevel.PADES_BASELINE_T,
			VisibleSignatureParameters(page = 1, x = 50f, y = 50f, width = 220f, height = 60f, text = "OmniSign"),
		)
		
		val result = pdfaResult(signed)
		withClue(result.errorMessages) { result.isCompliant.shouldBeTrue() }
	}
	
	test("repeated archival re-timestamping never breaks conformance") {
		var current = sign(pdfA3b(), SignatureLevel.PADES_BASELINE_T)
		
		repeat(3) { round ->
			current = archivingRepository.extendDocument(
				ArchivingParameters(
					inputBytes = current,
					inputName = "input.pdf",
					targetLevel = SignatureLevel.PADES_BASELINE_LTA,
					resolvedConfig = resolvedConfig(
						GlobalConfig(timestampServer = TimestampServerConfig(url = tsa.url)),
					),
				)
			).shouldBeRight().outputBytes
			
			val result = pdfaResult(current)
			withClue("round ${round + 1}: ${result.errorMessages}") { result.isCompliant.shouldBeTrue() }
		}
		
		Loader.loadPDF(current).use { it.signatureDictionaries.shouldNotBeEmpty() }
	}
	
	/**
	 * The rest of the PDF/A family. B-B and B-LTA bracket the range — no timestamp at all against a
	 * signature, a `/DSS` dictionary and an archive timestamp — so the levels between them add
	 * nothing here; PDF/A-3b keeps the exhaustive per-level sweep above.
	 */
	PDFA_VARIANTS.forEach { variant ->
		test("the generated ${variant.label} fixture really is conformant before anything signs it") {
			val result = pdfaResult(pdfaFixture(variant.part, variant.conformance, variant.version))
			
			result.profileId shouldBe variant.profileId
			withClue(result.errorMessages) { result.isCompliant.shouldBeTrue() }
		}
		
		listOf(SignatureLevel.PADES_BASELINE_B, SignatureLevel.PADES_BASELINE_LTA).forEach { level ->
			test("signing a ${variant.label} document at $level keeps it conformant") {
				val signed = sign(pdfaFixture(variant.part, variant.conformance, variant.version), level)
				
				val result = pdfaResult(signed)
				result.profileId shouldBe variant.profileId
				withClue(result.errorMessages) { result.isCompliant.shouldBeTrue() }
			}
		}
	}
}) {
	
	/**
	 * A PDF/A variant to build a fixture for: its part, conformance level (null where the part has
	 * none), the PDF version its fixture must declare, and the profile veraPDF reports back.
	 */
	private data class PdfaVariant(
		val label: String,
		val part: Int,
		val conformance: String?,
		val version: Float,
		val profileId: String,
	)
	
	private companion object {
		/**
		 * ISO 32000-1 Annex C Table C.1, made normative for PDF/A-1/2/3. Mirrors the constant of the
		 * same meaning in [DssServiceFactory], which is not visible from here.
		 */
		const val PDFA_MAX_STRING_LENGTH = 32_767
		
		/** PDF/A-4 dropped conformance levels and moved to PDF 2.0, so its fixtures differ. */
		const val PDFA_PART_4 = 4
		
		/** Cross-reference streams arrived in PDF 1.5; PDF/A-1, built on PDF 1.4, forbids them. */
		const val PDF_XREF_STREAM_VERSION = 1.5f
		
		/**
		 * Every PDF/A variant a fixture can be generated for.
		 *
		 * Conformance level A is absent because it demands a tagged, structured document that cannot
		 * be produced meaningfully by hand here; it is covered instead by a real tagged PDF/A-3A
		 * checked outside the suite.
		 *
		 * Only parts 2 and 3 guard the `/Contents` reservation. veraPDF's part 1 profile has no
		 * string-length rule at all — the rule there that mentions 32767 bounds the range of real
		 * values (clause 6.1.12) — so an over-large reservation is reported conformant as PDF/A-1B
		 * while parts 2 and 3 reject it; part 4 is exempt because PDF 2.0 dropped the limit. The
		 * part 1 and part 4 entries therefore guard everything *else* signing could break, most
		 * usefully that the signature's incremental revision introduces no cross-reference stream,
		 * which PDF 1.4 forbids.
		 */
		val PDFA_VARIANTS = listOf(
			PdfaVariant("PDF/A-1b", 1, "B", 1.4f, "PDF/A-1B"),
			PdfaVariant("PDF/A-2b", 2, "B", 1.7f, "PDF/A-2B"),
			PdfaVariant("PDF/A-2u", 2, "U", 1.7f, "PDF/A-2U"),
			PdfaVariant("PDF/A-3u", 3, "U", 1.7f, "PDF/A-3U"),
			PdfaVariant("PDF/A-4", PDFA_PART_4, null, 2.0f, "PDF/A-4NO_LEVEL"),
			PdfaVariant("PDF/A-4e", PDFA_PART_4, "E", 2.0f, "PDF/A-4E"),
			PdfaVariant("PDF/A-4f", PDFA_PART_4, "F", 2.0f, "PDF/A-4F"),
		)
	}
}
