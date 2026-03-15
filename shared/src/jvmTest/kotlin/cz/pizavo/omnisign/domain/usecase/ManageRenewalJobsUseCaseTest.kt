package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

/**
 * Verifies [ManageRenewalJobsUseCase] CRUD operations and error handling
 * using Arrow-specific Kotest matchers.
 */
class ManageRenewalJobsUseCaseTest : FunSpec({
	
	val configRepository: ConfigRepository = mockk()
	val useCase = ManageRenewalJobsUseCase(configRepository)
	
	val baseConfig = AppConfig()
	
	beforeTest { clearMocks(configRepository) }
	
	fun job(name: String, globs: List<String> = listOf("/docs/**/*.pdf")) =
		RenewalJob(name = name, globs = globs)
	
	test("upsert saves a new job") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.upsert(job("j1")).shouldBeRight()
		
		saved.captured.renewalJobs.shouldHaveSize(1)
		saved.captured.renewalJobs["j1"]?.name shouldBe "j1"
	}
	
	test("upsert replaces an existing job") {
		val existing = baseConfig.copy(renewalJobs = mapOf("j1" to job("j1", listOf("/old/**"))))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.upsert(job("j1", listOf("/new/**"))).shouldBeRight()
		
		saved.captured.renewalJobs["j1"]?.globs shouldBe listOf("/new/**")
	}
	
	test("remove deletes existing job") {
		val existing = baseConfig.copy(renewalJobs = mapOf("j1" to job("j1")))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.remove("j1").shouldBeRight()
		
		saved.captured.renewalJobs.shouldBeEmpty()
	}
	
	test("remove returns error for unknown job") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.remove("nonexistent").shouldBeLeft()
		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}
	
	test("get returns correct job") {
		val config = baseConfig.copy(renewalJobs = mapOf("j1" to job("j1")))
		coEvery { configRepository.getCurrentConfig() } returns config
		
		useCase.get("j1").shouldBeRight().name shouldBe "j1"
	}
	
	test("get returns error for unknown job") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.get("missing").shouldBeLeft()
	}
	
	test("list returns all jobs") {
		val config = baseConfig.copy(
			renewalJobs = mapOf("a" to job("a"), "b" to job("b"))
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		
		val jobs = useCase.list().shouldBeRight()
		jobs.keys shouldBe setOf("a", "b")
	}
	
	test("list returns empty map when no jobs configured") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.list().shouldBeRight().shouldBeEmpty()
	}
})

