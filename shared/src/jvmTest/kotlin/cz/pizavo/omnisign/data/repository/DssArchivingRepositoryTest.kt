package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * Verifies [DssArchivingRepository] error handling and lightweight PDF inspection
 * using Arrow [arrow.core.Either] matchers.
 */
class DssArchivingRepositoryTest : FunSpec({
	
	val tmpDir = tempdir()
	
	val configRepository: ConfigRepository = mockk()
	val dssServiceFactory: DssServiceFactory = mockk(relaxed = true)
	
	val repository = DssArchivingRepository(configRepository, dssServiceFactory)
	
	fun configWithoutTsa() = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA
		)
	)
	
	fun tmpFile(name: String) = File(tmpDir, name).also { it.createNewFile() }
	
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
	
	test("extendDocument returns ExtensionFailed when input file does not exist") {
		coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()
		
		repository.extendDocument(
			ArchivingParameters(
				inputFile = "/nonexistent/signed.pdf",
				outputFile = tmpFile("out.pdf").absolutePath
			)
		).shouldBeLeft().shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
	
	test("extendDocument returns ExtensionFailed when no TSA is configured") {
		coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()
		
		repository.extendDocument(
			ArchivingParameters(
				inputFile = tmpFile("signed.pdf").absolutePath,
				outputFile = tmpFile("out2.pdf").absolutePath,
				targetLevel = SignatureLevel.PADES_BASELINE_T
			)
		).shouldBeLeft().shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
	
	test("extendDocument returns ExtensionFailed when target level is B-B") {
		coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()
		
		repository.extendDocument(
			ArchivingParameters(
				inputFile = tmpFile("signed2.pdf").absolutePath,
				outputFile = tmpFile("out3.pdf").absolutePath,
				targetLevel = SignatureLevel.PADES_BASELINE_B
			)
		).shouldBeLeft().shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
	
	test("needsArchivalRenewal returns ExtensionFailed for a non-existent file") {
		repository.needsArchivalRenewal("/nonexistent/doc.pdf")
			.shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
	
	test("getDocumentTimestampInfo returns ExtensionFailed for a non-existent file") {
		repository.getDocumentTimestampInfo("/nonexistent/doc.pdf")
			.shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
	
	test("getDocumentTimestampInfo reports no timestamps and no LT data for a plain PDF") {
		val pdf = createPlainPdf("plain.pdf")
		val info = repository.getDocumentTimestampInfo(pdf.absolutePath).shouldBeRight()
		info.hasDocumentTimestamp.shouldBeFalse()
		info.containsLtData.shouldBeFalse()
	}
	
	test("getDocumentTimestampInfo detects LT data when DSS dictionary is present") {
		val pdf = createPdfWithDssDictionary("with-dss.pdf")
		val info = repository.getDocumentTimestampInfo(pdf.absolutePath).shouldBeRight()
		info.hasDocumentTimestamp.shouldBeFalse()
		info.containsLtData.shouldBeTrue()
	}
	
	test("getDocumentTimestampInfo returns error for a corrupt file") {
		val corrupt = File(tmpDir, "corrupt.pdf").also { it.writeText("not a PDF") }
		repository.getDocumentTimestampInfo(corrupt.absolutePath)
			.shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
	}
})

