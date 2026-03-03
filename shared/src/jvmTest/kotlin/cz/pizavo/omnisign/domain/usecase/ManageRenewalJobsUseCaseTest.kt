package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [ManageRenewalJobsUseCase].
 *
 * Uses a mock [ConfigRepository] and verifies that each operation reads the current config,
 * transforms the [AppConfig.renewalJobs] map correctly, and persists the result.
 */
class ManageRenewalJobsUseCaseTest {
	
	private val configRepository: ConfigRepository = mockk()
	private val useCase = ManageRenewalJobsUseCase(configRepository)
	
	private val baseConfig = AppConfig()
	
	private fun job(name: String, globs: List<String> = listOf("/docs/**/*.pdf")) =
		RenewalJob(name = name, globs = globs)
	
	@Test
	fun `upsert saves a new job`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.upsert(job("j1")).also { assertIs<arrow.core.Either.Right<Unit>>(it) }
		
		assertEquals(1, saved.captured.renewalJobs.size)
		assertEquals("j1", saved.captured.renewalJobs["j1"]?.name)
	}
	
	@Test
	fun `upsert replaces an existing job`() = runBlocking<Unit> {
		val existing = baseConfig.copy(renewalJobs = mapOf("j1" to job("j1", listOf("/old/**"))))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.upsert(job("j1", listOf("/new/**"))).also { assertIs<arrow.core.Either.Right<Unit>>(it) }
		
		assertEquals(listOf("/new/**"), saved.captured.renewalJobs["j1"]?.globs)
	}
	
	@Test
	fun `remove deletes existing job`() = runBlocking<Unit> {
		val existing = baseConfig.copy(renewalJobs = mapOf("j1" to job("j1")))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.remove("j1").also { assertIs<arrow.core.Either.Right<Unit>>(it) }
		
		assertTrue(saved.captured.renewalJobs.isEmpty())
	}
	
	@Test
	fun `remove returns error for unknown job`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		val result = useCase.remove("nonexistent")
		
		assertIs<arrow.core.Either.Left<*>>(result)
		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}
	
	@Test
	fun `get returns correct job`() = runBlocking<Unit> {
		val config = baseConfig.copy(renewalJobs = mapOf("j1" to job("j1")))
		coEvery { configRepository.getCurrentConfig() } returns config
		
		val result = useCase.get("j1")
		
		assertIs<arrow.core.Either.Right<RenewalJob>>(result)
		assertEquals("j1", result.value.name)
	}
	
	@Test
	fun `get returns error for unknown job`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		assertIs<arrow.core.Either.Left<*>>(useCase.get("missing"))
	}
	
	@Test
	fun `list returns all jobs`() = runBlocking<Unit> {
		val config = baseConfig.copy(
			renewalJobs = mapOf("a" to job("a"), "b" to job("b"))
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		
		val result = useCase.list()
		
		assertIs<arrow.core.Either.Right<Map<String, RenewalJob>>>(result)
		assertEquals(setOf("a", "b"), result.value.keys)
	}
	
	@Test
	fun `list returns empty map when no jobs configured`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		val result = useCase.list()
		
		assertIs<arrow.core.Either.Right<Map<String, RenewalJob>>>(result)
		assertTrue(result.value.isEmpty())
	}
}
