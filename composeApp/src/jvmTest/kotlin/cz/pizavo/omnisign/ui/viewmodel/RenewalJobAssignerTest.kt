package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [RenewalJobAssigner].
 */
class RenewalJobAssignerTest : FunSpec({

	val configRepository = mockk<ConfigRepository>()

	test("globMatchesFile matches literal path") {
		RenewalJobAssigner.globMatchesFile("/tmp/doc.pdf", "/tmp/doc.pdf") shouldBe true
	}

	test("globMatchesFile rejects non-matching literal path") {
		RenewalJobAssigner.globMatchesFile("/tmp/doc.pdf", "/tmp/other.pdf") shouldBe false
	}

	test("globMatchesFile matches star wildcard") {
		RenewalJobAssigner.globMatchesFile("/tmp/*.pdf", "/tmp/doc.pdf") shouldBe true
	}

	test("globMatchesFile star does not cross directory boundaries") {
		RenewalJobAssigner.globMatchesFile("/tmp/*.pdf", "/tmp/sub/doc.pdf") shouldBe false
	}

	test("globMatchesFile matches double-star pattern") {
		RenewalJobAssigner.globMatchesFile("/tmp/**/*.pdf", "/tmp/sub/deep/doc.pdf") shouldBe true
	}

	test("globMatchesFile matches question mark") {
		RenewalJobAssigner.globMatchesFile("/tmp/doc?.pdf", "/tmp/doc1.pdf") shouldBe true
		RenewalJobAssigner.globMatchesFile("/tmp/doc?.pdf", "/tmp/doc12.pdf") shouldBe false
	}

	test("globMatchesFile normalises backslashes") {
		RenewalJobAssigner.globMatchesFile("C:\\docs\\*.pdf", "C:/docs/test.pdf") shouldBe true
	}

	test("globMatchesFile is case-insensitive") {
		RenewalJobAssigner.globMatchesFile("C:\\Users\\Vojta\\Desktop\\doc.pdf", "c:/users/vojta/desktop/doc.pdf") shouldBe true
	}

	test("findCoveringJob finds matching job") {
		val job = RenewalJob(
			name = "docs-job",
			globs = listOf("/docs/**/*.pdf"),
			renewalBufferDays = 30,
			profile = "prod",
		)
		val result = RenewalJobAssigner.findCoveringJob(
			filePath = "/docs/subdir/report.pdf",
			jobs = listOf(job),
		)
		result.shouldNotBeNull()
		result.name shouldBe "docs-job"
	}

	test("findCoveringJob matches regardless of buffer days") {
		val job = RenewalJob(
			name = "docs-job",
			globs = listOf("/docs/**/*.pdf"),
			renewalBufferDays = 60,
			profile = "prod",
		)
		RenewalJobAssigner.findCoveringJob(
			filePath = "/docs/report.pdf",
			jobs = listOf(job),
		).shouldNotBeNull()
	}

	test("findCoveringJob matches regardless of profile") {
		val job = RenewalJob(
			name = "docs-job",
			globs = listOf("/docs/**/*.pdf"),
			renewalBufferDays = 30,
			profile = "dev",
		)
		RenewalJobAssigner.findCoveringJob(
			filePath = "/docs/report.pdf",
			jobs = listOf(job),
		).shouldNotBeNull()
	}

	test("findCoveringJob returns null when no glob matches") {
		val job = RenewalJob(
			name = "docs-job",
			globs = listOf("/other/**/*.pdf"),
			renewalBufferDays = 30,
			profile = null,
		)
		RenewalJobAssigner.findCoveringJob(
			filePath = "/docs/report.pdf",
			jobs = listOf(job),
		).shouldBeNull()
	}

	test("findCoveringJob is case-insensitive for Windows paths") {
		val job = RenewalJob(
			name = "win-job",
			globs = listOf("C:\\Users\\Vojta\\Desktop\\*.pdf"),
		)
		RenewalJobAssigner.findCoveringJob(
			filePath = "c:\\Users\\Vojta\\Desktop\\report.pdf",
			jobs = listOf(job),
		).shouldNotBeNull()
	}

	test("buildOfferState populates existing jobs and detects coverage") {
		runTest {
			val job = RenewalJob(
				name = "archive",
				globs = listOf("/archive/**/*.pdf"),
				profile = "prod",
			)
			val appConfig = AppConfig(
				global = GlobalConfig(),
				profiles = mapOf("prod" to cz.pizavo.omnisign.domain.model.config.ProfileConfig(name = "prod")),
				activeProfile = "prod",
				renewalJobs = mapOf("archive" to job),
			)
			coEvery { configRepository.getCurrentConfig() } returns appConfig

			val assigner = RenewalJobAssigner(configRepository)
			val offer = assigner.buildOfferState("/archive/deep/doc.pdf")

			offer.existingJobs.size shouldBe 1
			offer.availableProfiles shouldBe listOf("prod")
			offer.activeProfile shouldBe "prod"
			offer.coveringJob.shouldNotBeNull().name shouldBe "archive"
		}
	}

	test("buildOfferState returns null coveringJob when no match") {
		runTest {
			val job = RenewalJob(name = "other", globs = listOf("/other/*.pdf"))
			val appConfig = AppConfig(
				global = GlobalConfig(),
				renewalJobs = mapOf("other" to job),
			)
			coEvery { configRepository.getCurrentConfig() } returns appConfig

			val assigner = RenewalJobAssigner(configRepository)
			val offer = assigner.buildOfferState("/docs/report.pdf")

			offer.coveringJob.shouldBeNull()
		}
	}

	test("assignToExistingJob appends glob and saves") {
		runTest {
			val job = RenewalJob(name = "myjob", globs = listOf("/old/*.pdf"))
			val appConfig = AppConfig(
				global = GlobalConfig(),
				renewalJobs = mapOf("myjob" to job),
			)
			coEvery { configRepository.getCurrentConfig() } returns appConfig
			val saved = slot<AppConfig>()
			coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

			val assigner = RenewalJobAssigner(configRepository)
			val result = assigner.assignToExistingJob("myjob", "/new/doc.pdf")

			result shouldBe "myjob"
			saved.captured.renewalJobs["myjob"]!!.globs shouldBe listOf("/old/*.pdf", "/new/doc.pdf")
			coVerify(exactly = 1) { configRepository.saveConfig(any()) }
		}
	}

	test("assignToExistingJob returns null for unknown job") {
		runTest {
			val appConfig = AppConfig(global = GlobalConfig(), renewalJobs = emptyMap())
			coEvery { configRepository.getCurrentConfig() } returns appConfig

			val assigner = RenewalJobAssigner(configRepository)
			assigner.assignToExistingJob("nonexistent", "/new/doc.pdf").shouldBeNull()
		}
	}

	test("createNewJob saves and returns success") {
		runTest {
			val appConfig = AppConfig(global = GlobalConfig(), renewalJobs = emptyMap())
			coEvery { configRepository.getCurrentConfig() } returns appConfig
			val saved = slot<AppConfig>()
			coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

			val assigner = RenewalJobAssigner(configRepository)
			val job = RenewalJob(name = "newjob", globs = listOf("/docs/file.pdf"))
			val result = assigner.createNewJob(job)

			result.isSuccess shouldBe true
			result.getOrNull() shouldBe "newjob"
			saved.captured.renewalJobs["newjob"]!!.globs shouldBe listOf("/docs/file.pdf")
		}
	}

	test("createNewJob returns failure for duplicate name") {
		runTest {
			val existing = RenewalJob(name = "dup", globs = listOf("/x/*.pdf"))
			val appConfig = AppConfig(
				global = GlobalConfig(),
				renewalJobs = mapOf("dup" to existing),
			)
			coEvery { configRepository.getCurrentConfig() } returns appConfig

			val assigner = RenewalJobAssigner(configRepository)
			val result = assigner.createNewJob(RenewalJob(name = "dup", globs = listOf("/y/*.pdf")))

			result.isFailure shouldBe true
		}
	}
})





