package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File

/**
 * Unit tests for [RenewBatchUseCase].
 */
class RenewBatchUseCaseTest : FunSpec({

    val tmpDir = tempdir()
    val archivingRepository: ArchivingRepository = mockk()
    val configRepository: ConfigRepository = mockk()

    val checkRenewal = CheckArchivalRenewalUseCase(archivingRepository)
    val extend = ExtendDocumentUseCase(archivingRepository)

    beforeTest {
        clearMocks(archivingRepository, configRepository)
    }

    fun subDir(name: String) = File(tmpDir, name).also { it.mkdirs() }

    fun globDir(dir: File) = dir.absolutePath.replace('\\', '/') + "/*.pdf"

    val baseGlobal = GlobalConfig(
        defaultHashAlgorithm = HashAlgorithm.SHA256,
        defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA,
        timestampServer = TimestampServerConfig(url = "https://tsa.example.com"),
    )
    val baseConfig = AppConfig(global = baseGlobal)

    fun useCaseWith(appConfig: AppConfig): RenewBatchUseCase {
        coEvery { configRepository.getCurrentConfig() } returns appConfig
        return RenewBatchUseCase(checkRenewal, extend, configRepository)
    }

    test("returns null when requested job name does not exist") {
        val uc = useCaseWith(baseConfig)
        val result = uc(jobName = "nonexistent")
        result.shouldBeNull()
    }

    test("returns empty jobs list when no renewal jobs configured") {
        val uc = useCaseWith(baseConfig)
        val result = uc()
        result.shouldNotBeNull()
        result.jobs shouldHaveSize 0
        result.checked shouldBe 0
    }

    test("skips files not needing renewal") {
        val dir = subDir("skip")
        val file = File(dir, "skip-ok.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns false.right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.skipped shouldBe 1
        result.renewed shouldBe 0
        result.errors shouldBe 0
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.SKIPPED
    }

    test("renews files needing renewal in-place") {
        val dir = subDir("renew")
        val file = File(dir, "renew-expiring.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()
        coEvery {
            archivingRepository.extendDocument(
                match { it.inputFile == file.absolutePath && it.outputFile == file.absolutePath }
            )
        } returns ArchivingResult(
            outputFile = file.absolutePath,
            newSignatureLevel = "PAdES-BASELINE-LTA",
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.skipped shouldBe 0
        result.errors shouldBe 0
        result.success shouldBe true
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.RENEWED
    }

    test("dry-run mode does not modify files") {
        val dir = subDir("dry-run")
        val file = File(dir, "dry-run.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = useCaseWith(config)
        val result = uc(dryRun = true)

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.dryRun shouldBe true
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.DRY_RUN
        coVerify(exactly = 0) { archivingRepository.extendDocument(any()) }
    }

    test("extension error is isolated — other files continue") {
        val dir = subDir("iso-ext")
        val bad = File(dir, "iso-bad.pdf").also { it.createNewFile() }
        val good = File(dir, "iso-good.pdf").also { it.createNewFile() }

        coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } returns true.right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputFile == bad.absolutePath })
        } returns ArchivingError.ExtensionFailed("boom").left()
        coEvery {
            archivingRepository.extendDocument(match { it.inputFile == good.absolutePath })
        } returns ArchivingResult(
            outputFile = good.absolutePath,
            newSignatureLevel = "PAdES-BASELINE-LTA",
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.errors shouldBe 1
        result.success shouldBe false
    }

    test("check error is isolated — other files still processed") {
        val dir = subDir("iso-chk")
        val bad = File(dir, "chk-bad.pdf").also { it.createNewFile() }
        val good = File(dir, "chk-good.pdf").also { it.createNewFile() }

        coEvery {
            archivingRepository.needsArchivalRenewal(bad.absolutePath, any())
        } returns ArchivingError.ExtensionFailed("check failed").left()
        coEvery {
            archivingRepository.needsArchivalRenewal(good.absolutePath, any())
        } returns false.right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.renewed shouldBe 0
        result.skipped shouldBe 1
        result.errors shouldBe 1
    }

    test("renewal buffer from job is forwarded to check use case") {
        val dir = subDir("buf-fwd")
        val file = File(dir, "buf-fwd.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, 14) } returns false.right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), renewalBufferDays = 14)
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = useCaseWith(config)
        uc()

        coVerify(exactly = 1) { archivingRepository.needsArchivalRenewal(file.absolutePath, 14) }
    }

    test("runs only the specified job when jobName is provided") {
        val sub1 = File(tmpDir, "sub1").also { it.mkdirs() }
        val sub2 = File(tmpDir, "sub2").also { it.mkdirs() }
        File(sub1, "job1.pdf").createNewFile()
        val file2 = File(sub2, "job2.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } returns false.right()

        val glob1 = sub1.absolutePath.replace('\\', '/') + "/*.pdf"
        val glob2 = sub2.absolutePath.replace('\\', '/') + "/*.pdf"
        val job1 = RenewalJob(name = "first", globs = listOf(glob1))
        val job2 = RenewalJob(name = "second", globs = listOf(glob2))
        val config = baseConfig.copy(
            renewalJobs = mapOf("first" to job1, "second" to job2),
        )
        val uc = useCaseWith(config)
        val result = uc(jobName = "first")

        result.shouldNotBeNull()
        result.jobs shouldHaveSize 1
        result.jobs.first().name shouldBe "first"
        coVerify(exactly = 0) { archivingRepository.needsArchivalRenewal(file2.absolutePath, any()) }
    }

    test("job with invalid config produces CONFIG_ERROR status") {
        val sub = File(tmpDir, "bad-cfg").also { it.mkdirs() }
        File(sub, "doc.pdf").createNewFile()

        val global = baseGlobal.copy(
            disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
        )
        val profile = ProfileConfig(name = "broken")
        val glob = sub.absolutePath.replace('\\', '/') + "/*.pdf"
        val job = RenewalJob(name = "j", globs = listOf(glob), profile = "broken")
        val config = AppConfig(
            global = global,
            profiles = mapOf("broken" to profile),
            renewalJobs = mapOf("j" to job),
        )
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.errors shouldBe 1
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.CONFIG_ERROR
    }

    test("preserves notify flag from job in result") {
        val sub1 = File(tmpDir, "notify-loud").also { it.mkdirs() }
        val sub2 = File(tmpDir, "notify-quiet").also { it.mkdirs() }

        val uc = useCaseWith(
            baseConfig.copy(
                renewalJobs = mapOf(
                    "loud" to RenewalJob(
                        name = "loud",
                        globs = listOf(sub1.absolutePath.replace('\\', '/') + "/*.pdf"),
                        notify = true,
                    ),
                    "quiet" to RenewalJob(
                        name = "quiet",
                        globs = listOf(sub2.absolutePath.replace('\\', '/') + "/*.pdf"),
                        notify = false,
                    ),
                ),
            )
        )
        val result = uc()

        result.shouldNotBeNull()
        result.jobs.find { it.name == "loud" }!!.notify shouldBe true
        result.jobs.find { it.name == "quiet" }!!.notify shouldBe false
    }

    test("resolveGlobs returns files matching glob pattern") {
        val sub = File(tmpDir, "glob-test").also { it.mkdirs() }
        File(sub, "a.pdf").createNewFile()
        File(sub, "b.pdf").createNewFile()
        File(sub, "readme.txt").createNewFile()

        val uc = useCaseWith(baseConfig)
        val glob = sub.absolutePath.replace('\\', '/') + "/*.pdf"
        val files = uc.resolveGlobs(listOf(glob))

        files shouldHaveSize 2
        files.all { it.extension == "pdf" } shouldBe true
    }

    test("resolveGlobs handles non-existent root gracefully") {
        val uc = useCaseWith(baseConfig)
        val nonExistent = File(tmpDir, "nonexistent").absolutePath.replace('\\', '/') + "/*.pdf"
        val files = uc.resolveGlobs(listOf(nonExistent))
        files shouldHaveSize 0
    }
})











