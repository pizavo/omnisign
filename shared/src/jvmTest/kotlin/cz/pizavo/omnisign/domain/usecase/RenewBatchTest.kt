package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * Tests for the renewal batch logic that underpins the `omnisign renew` CLI command.
 *
 * Verifies that:
 * - Files not needing renewal are skipped without calling [ExtendDocumentUseCase].
 * - Files needing renewal are extended in-place (inputFile == outputFile).
 * - Extension errors are isolated: other files in the same job continue processing.
 * - Check errors are isolated similarly.
 * - The renewal buffer is forwarded from the [RenewalJob] to [CheckArchivalRenewalUseCase].
 */
class RenewBatchTest {
	
	@get:Rule
	val tmp = TemporaryFolder()
	
	private val archivingRepository: ArchivingRepository = mockk()
	private val configRepository: ConfigRepository = mockk()
	
	private val checkRenewal = CheckArchivalRenewalUseCase(archivingRepository)
	private val extend = ExtendDocumentUseCase(archivingRepository)
	
	/**
	 * Simulate one full renewal batch pass over [jobs], mimicking the core logic in
	 * `Renew.run()`, and return (renewed, skipped, errors).
	 */
	private suspend fun runBatch(
		jobs: Map<String, RenewalJob>,
		files: Map<String, List<String>>,
	): Triple<Int, Int, Int> {
		var renewed = 0
		var skipped = 0
		var errors = 0
		for ((_, job) in jobs) {
			val jobFiles = files[job.name] ?: emptyList()
			for (path in jobFiles) {
				checkRenewal(path, job.renewalBufferDays).fold(
					ifLeft = { errors++ },
					ifRight = { needs ->
						if (!needs) {
							skipped++; return@fold
						}
						extend(
							ArchivingParameters(
								inputFile = path,
								outputFile = path,
							)
						).fold(ifLeft = { errors++ }, ifRight = { renewed++ })
					}
				)
			}
		}
		return Triple(renewed, skipped, errors)
	}
	
	@Test
	fun `files not needing renewal are skipped`() = runBlocking<Unit> {
		val file = tmp.newFile("ok.pdf").absolutePath
		coEvery { archivingRepository.needsArchivalRenewal(file, any()) } returns false.right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(file)))
		
		assertEquals(0, renewed)
		assertEquals(1, skipped)
		assertEquals(0, errors)
		coVerify(exactly = 0) { archivingRepository.extendDocument(any()) }
	}
	
	@Test
	fun `files needing renewal are extended in-place`() = runBlocking<Unit> {
		val file = tmp.newFile("expiring.pdf").absolutePath
		coEvery { archivingRepository.needsArchivalRenewal(file, any()) } returns true.right()
		coEvery { archivingRepository.extendDocument(match { it.inputFile == file && it.outputFile == file }) } returns
				ArchivingResult(outputFile = file, newSignatureLevel = "PADES_BASELINE_LTA").right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(file)))
		
		assertEquals(1, renewed)
		assertEquals(0, skipped)
		assertEquals(0, errors)
	}
	
	@Test
	fun `extension error is isolated — other files in the job continue`() = runBlocking<Unit> {
		val bad = tmp.newFile("bad.pdf").absolutePath
		val good = tmp.newFile("good.pdf").absolutePath
		
		coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } returns true.right()
		coEvery { archivingRepository.extendDocument(match { it.inputFile == bad }) } returns
				ArchivingError.ExtensionFailed("boom").left()
		coEvery { archivingRepository.extendDocument(match { it.inputFile == good }) } returns
				ArchivingResult(outputFile = good, newSignatureLevel = "PADES_BASELINE_LTA").right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(bad, good)))
		
		assertEquals(1, renewed)
		assertEquals(0, skipped)
		assertEquals(1, errors)
	}
	
	@Test
	fun `check error is isolated — other files still processed`() = runBlocking<Unit> {
		val bad = tmp.newFile("bad.pdf").absolutePath
		val good = tmp.newFile("good.pdf").absolutePath
		
		coEvery { archivingRepository.needsArchivalRenewal(bad, any()) } returns
				ArchivingError.ExtensionFailed("check failed").left()
		coEvery { archivingRepository.needsArchivalRenewal(good, any()) } returns false.right()
		
		val job = RenewalJob(name = "j", globs = emptyList())
		val (renewed, skipped, errors) = runBatch(mapOf("j" to job), mapOf("j" to listOf(bad, good)))
		
		assertEquals(0, renewed)
		assertEquals(1, skipped)
		assertEquals(1, errors)
	}
	
	@Test
	fun `renewal buffer from job is forwarded to check use case`() = runBlocking<Unit> {
		val file = tmp.newFile("f.pdf").absolutePath
		coEvery { archivingRepository.needsArchivalRenewal(file, 14) } returns false.right()
		
		val job = RenewalJob(name = "j", globs = emptyList(), renewalBufferDays = 14)
		runBatch(mapOf("j" to job), mapOf("j" to listOf(file)))
		
		coVerify(exactly = 1) { archivingRepository.needsArchivalRenewal(file, 14) }
	}
}
