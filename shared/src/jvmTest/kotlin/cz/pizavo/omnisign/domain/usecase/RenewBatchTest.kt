package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File

/**
 * Verifies renewal batch logic: skipping, in-place extension, error isolation,
 * and buffer forwarding.
 */
class RenewBatchTest : FunSpec({
	
	val tmpDir = tempdir()
	
	val archivingRepository: ArchivingRepository = mockk()
	
	val checkRenewal = CheckArchivalRenewalUseCase(archivingRepository)
	val extend = ExtendDocumentUseCase(archivingRepository)
	
	fun tmpFile(name: String) = File(tmpDir, name).also { it.createNewFile() }
	
	suspend fun runBatch(
		jobs: Map<String, RenewalJob>,
		files: Map<String, List<String>>,
	): Triple<Int, Int, Int> {
		var renewed = 0; var skipped = 0; var errors = 0
		for ((_, job) in jobs) {
			val jobFiles = files[job.name] ?: emptyList()
			for (path in jobFiles) {
				checkRenewal(path, job.renewalBufferDays).fold(
					ifLeft = { errors++ },
					ifRight = { needs ->
						if (needs != RenewalNeed.NEEDED) { skipped++; return@fold }
						extend(ArchivingParameters(inputBytes = File(path).readBytes(), inputName = File(path).name))
							.fold(ifLeft = { errors++ }, ifRight = { renewed++ })
					}
				)
			}
		}
		return Triple(renewed, skipped, errors)
	}
	
	test("files not needing renewal are skipped") {
		val file = tmpFile("ok.pdf").absolutePath
		coEvery { archivingRepository.needsArchivalRenewal(file, any()) } returns RenewalNeed.NOT_NEEDED.right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(file)))
		
		renewed shouldBe 0
		skipped shouldBe 1
		errors shouldBe 0
		coVerify(exactly = 0) { archivingRepository.extendDocument(any()) }
	}
	
	test("files needing renewal are extended in-place") {
		val file = tmpFile("expiring.pdf").absolutePath
		coEvery { archivingRepository.needsArchivalRenewal(file, any()) } returns RenewalNeed.NEEDED.right()
		coEvery { archivingRepository.extendDocument(match { it.inputName == File(file).name }) } returns
			ArchivingResult(outputBytes = ByteArray(0), outputName = File(file).name, newSignatureLevel = "PADES_BASELINE_LTA").right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(file)))
		
		renewed shouldBe 1
		skipped shouldBe 0
		errors shouldBe 0
	}
	
	test("extension error is isolated — other files in the job continue") {
		val bad = tmpFile("bad.pdf").absolutePath
		val good = tmpFile("good.pdf").absolutePath
		
		coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } returns RenewalNeed.NEEDED.right()
		coEvery { archivingRepository.extendDocument(match { it.inputName == File(bad).name }) } returns
			ArchivingError.ExtensionFailed(LocalizableText.Literal("boom")).left()
		coEvery { archivingRepository.extendDocument(match { it.inputName == File(good).name }) } returns
			ArchivingResult(outputBytes = ByteArray(0), outputName = File(good).name, newSignatureLevel = "PADES_BASELINE_LTA").right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(bad, good)))
		
		renewed shouldBe 1
		skipped shouldBe 0
		errors shouldBe 1
	}
	
	test("check error is isolated — other files still processed") {
		val bad = tmpFile("bad2.pdf").absolutePath
		val good = tmpFile("good2.pdf").absolutePath
		
		coEvery { archivingRepository.needsArchivalRenewal(bad, any()) } returns
			ArchivingError.ExtensionFailed(LocalizableText.Literal("check failed")).left()
		coEvery { archivingRepository.needsArchivalRenewal(good, any()) } returns RenewalNeed.NOT_NEEDED.right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(bad, good)))
		
		renewed shouldBe 0
		skipped shouldBe 1
		errors shouldBe 1
	}
	
	test("renewal buffer from job is forwarded to check use case") {
		val file = tmpFile("f.pdf").absolutePath
		coEvery { archivingRepository.needsArchivalRenewal(file, 14) } returns RenewalNeed.NOT_NEEDED.right()
		
		val job = RenewalJob(name = "j", globs = emptyList(), renewalBufferDays = 14)
		runBatch(mapOf("j" to job), mapOf("j" to listOf(file)))
		
		coVerify(exactly = 1) { archivingRepository.needsArchivalRenewal(file, 14) }
	}
})

