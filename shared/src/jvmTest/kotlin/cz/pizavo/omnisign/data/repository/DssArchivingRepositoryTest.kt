package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File

/**
 * Verifies [DssArchivingRepository] error handling using Arrow [arrow.core.Either] matchers.
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
})

