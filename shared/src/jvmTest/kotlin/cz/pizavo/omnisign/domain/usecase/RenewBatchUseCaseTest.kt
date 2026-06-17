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
import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import cz.pizavo.omnisign.domain.port.RenewalLock
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlin.time.Instant

/**
 * Unit tests for [RenewBatchUseCase].
 */
class RenewBatchUseCaseTest : FunSpec({

    val tmpDir = tempdir()
    val archivingRepository: ArchivingRepository = mockk()
    val configRepository: ConfigRepository = mockk()

    val checkRenewal = CheckArchivalRenewalUseCase(archivingRepository)
    val extend = ExtendDocumentUseCase(archivingRepository)
    val grantingLock: RenewalLock = mockk { every { tryAcquire() } returns AutoCloseable {} }
    val runRecordStore: RenewalRunRecordStore = mockk(relaxed = true)

    beforeTest {
        clearMocks(archivingRepository, configRepository, runRecordStore)
        coEvery {
            archivingRepository.needsArchivalRenewal(match { it.contains(".verify.") }, any())
        } returns false.right()
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
        return RenewBatchUseCase(checkRenewal, extend, configRepository, grantingLock, runRecordStore)
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
                match { it.inputName == file.name }
            )
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = file.name,
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

        coEvery { archivingRepository.needsArchivalRenewal(bad.absolutePath, any()) } returns true.right()
        coEvery { archivingRepository.needsArchivalRenewal(good.absolutePath, any()) } returns true.right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == bad.name })
        } returns ArchivingError.ExtensionFailed("boom").left()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == good.name })
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = good.name,
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

    test("resolveGlobs matches files in nested subdirectories") {
        val root = File(tmpDir, "nested-glob").also { it.mkdirs() }
        File(root, "a").mkdirs()
        File(root, "a/mid.pdf").createNewFile()
        File(root, "a/b").mkdirs()
        File(root, "a/b/deep.pdf").createNewFile()
        File(root, "a/b/notes.txt").createNewFile()

        val uc = useCaseWith(baseConfig)
        val glob = root.absolutePath.replace('\\', '/') + "/**/*.pdf"
        val files = uc.resolveGlobs(listOf(glob))

        files.map { it.name }.sorted() shouldContainExactly listOf("deep.pdf", "mid.pdf")
    }

    test("resolveGlobs restricts a broad wildcard to PDF files") {
        val sub = File(tmpDir, "broad-glob").also { it.mkdirs() }
        File(sub, "a.pdf").createNewFile()
        File(sub, "notes.txt").createNewFile()
        File(sub, "image.png").createNewFile()

        val uc = useCaseWith(baseConfig)
        val glob = sub.absolutePath.replace('\\', '/') + "/*"
        val files = uc.resolveGlobs(listOf(glob))

        files.map { it.name } shouldContainExactly listOf("a.pdf")
    }

    test("probeDirectoryWritable returns null and leaves no probe file for a writable directory") {
        val dir = subDir("probe-ok")
        val target = File(dir, "doc.pdf")

        val uc = useCaseWith(baseConfig)
        val error = uc.probeDirectoryWritable(target)

        error.shouldBeNull()
        dir.listFiles()!!.toList().shouldBeEmpty()
    }

    test("probeDirectoryWritable reports an error and leaks nothing when the directory is not writable") {
        val dir = subDir("probe-fail")
        File(dir, "occupied").writeText("a file, not a directory")
        val target = File(dir, "occupied/doc.pdf")

        val uc = useCaseWith(baseConfig)
        val error = uc.probeDirectoryWritable(target)

        error.shouldNotBeNull()
        dir.listFiles { f -> f.isFile }!!.map { it.name } shouldContainExactly listOf("occupied")
    }

    test("writeAtomically replaces existing file content and leaves no temporary files") {
        val dir = subDir("atomic-replace")
        val target = File(dir, "doc.pdf").apply { writeText("ORIGINAL") }

        val uc = useCaseWith(baseConfig)
        uc.writeAtomically(target, "RENEWED".toByteArray())

        target.readText() shouldBe "RENEWED"
        dir.listFiles()!!.map { it.name } shouldContainExactly listOf("doc.pdf")
    }

    test("writeAtomically creates the target when it does not yet exist") {
        val dir = subDir("atomic-create")
        val target = File(dir, "fresh.pdf")

        val uc = useCaseWith(baseConfig)
        uc.writeAtomically(target, "DATA".toByteArray())

        target.readText() shouldBe "DATA"
    }

    test("writeAtomically preserves the original and cleans up its temp file when the move fails") {
        val dir = subDir("atomic-fail")
        val unreplaceableTarget = File(dir, "occupied").apply { mkdirs() }
        val sentinel = File(unreplaceableTarget, "keep.txt").apply { writeText("ORIGINAL") }

        val uc = useCaseWith(baseConfig)
        shouldThrow<Exception> { uc.writeAtomically(unreplaceableTarget, "RENEWED".toByteArray()) }

        sentinel.readText() shouldBe "ORIGINAL"
        dir.listFiles { f -> f.isFile }!!.toList().shouldBeEmpty()
    }

    test("returns an alreadyRunning result and does no work when the lock is held") {
        val busyLock: RenewalLock = mockk { every { tryAcquire() } returns null }
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, busyLock, runRecordStore)

        val result = uc()

        result.shouldNotBeNull()
        result.alreadyRunning shouldBe true
        result.checked shouldBe 0
        coVerify(exactly = 0) { archivingRepository.needsArchivalRenewal(any(), any()) }
    }

    test("releases the renewal lock after the batch completes") {
        val handle = mockk<AutoCloseable>(relaxed = true)
        val lock: RenewalLock = mockk { every { tryAcquire() } returns handle }
        coEvery { configRepository.getCurrentConfig() } returns baseConfig
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, lock, runRecordStore)

        uc()

        verify { handle.close() }
    }

    test("reports a lock error and does no work when the lock cannot be acquired") {
        val failingLock: RenewalLock = mockk { every { tryAcquire() } throws IOException("disk on fire") }
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, failingLock, runRecordStore)

        val result = uc()

        result.shouldNotBeNull()
        result.lockError shouldBe "disk on fire"
        result.success shouldBe false
        coVerify(exactly = 0) { archivingRepository.needsArchivalRenewal(any(), any()) }
    }

    test("writes a timestamped backup of the original before renewing in place") {
        val dir = subDir("backup-write")
        val file = File(dir, "doc.pdf").apply { writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA").right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 3)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        file.readText() shouldBe "RENEWED"
        val backups = dir.listFiles { f -> f.name.endsWith(".bak") }!!.toList()
        backups shouldHaveSize 1
        backups.first().readText() shouldBe "ORIGINAL"
    }

    test("writes no backup when backupRetention is zero") {
        val dir = subDir("backup-off")
        val file = File(dir, "doc.pdf").apply { writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA").right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        uc()

        file.readText() shouldBe "RENEWED"
        dir.listFiles { f -> f.name.endsWith(".bak") }!!.toList().shouldBeEmpty()
    }

    test("pruneBackups keeps only the newest N and ignores unrelated files") {
        val dir = subDir("backup-prune")
        val file = File(dir, "doc.pdf").apply { writeText("live") }
        listOf("20200101T000000Z", "20210101T000000Z", "20220101T000000Z", "20230101T000000Z").forEach {
            File(dir, "doc.pdf.$it.bak").writeText("backup-$it")
        }
        File(dir, "doc.pdf.bak").writeText("unrelated manual backup")

        val uc = useCaseWith(baseConfig)
        uc.pruneBackups(file, 2)

        val remaining = dir.listFiles { f -> f.name.endsWith(".bak") }!!.map { it.name }.sorted()
        remaining shouldContainExactly listOf(
            "doc.pdf.20220101T000000Z.bak",
            "doc.pdf.20230101T000000Z.bak",
            "doc.pdf.bak",
        )
    }

    test("keeps the original and errors when the renewed output still needs renewal") {
        val dir = subDir("verify-loop")
        val file = File(dir, "doc.pdf").apply { writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()
        coEvery {
            archivingRepository.needsArchivalRenewal(match { it.contains(".verify.") }, any())
        } returns true.right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA").right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 3)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        result.renewed shouldBe 0
        result.errors shouldBe 1
        file.readText() shouldBe "ORIGINAL"
        dir.listFiles { f -> f.name.endsWith(".bak") }!!.toList().shouldBeEmpty()
    }

    test("keeps the original and errors when the renewed output fails validation") {
        val dir = subDir("verify-bad")
        val file = File(dir, "doc.pdf").apply { writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()
        coEvery {
            archivingRepository.needsArchivalRenewal(match { it.contains(".verify.") }, any())
        } returns ArchivingError.ExtensionFailed("not a valid PDF").left()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "GARBAGE".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA").right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        result.renewed shouldBe 0
        result.errors shouldBe 1
        file.readText() shouldBe "ORIGINAL"
    }

    test("records a successful run, resetting the failure counter") {
        val dir = subDir("rec-success")
        val file = File(dir, "ok.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA").right()
        every { runRecordStore.load() } returns null

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        uc()

        verify {
            runRecordStore.save(
                match {
                    it.outcome == RenewalRunOutcome.SUCCESS &&
                        it.renewed == 1 &&
                        it.failuresSinceSuccess == 0 &&
                        it.lastSuccessAt != null
                }
            )
        }
    }

    test("records a partial run, incrementing failures and carrying last success forward") {
        val dir = subDir("rec-partial")
        val file = File(dir, "bad.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingError.ExtensionFailed("tsa down").left()
        val previousSuccess = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = previousSuccess,
            outcome = RenewalRunOutcome.SUCCESS,
            lastSuccessAt = previousSuccess,
            failuresSinceSuccess = 0,
        )

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        uc()

        verify {
            runRecordStore.save(
                match {
                    it.outcome == RenewalRunOutcome.COMPLETED_WITH_ERRORS &&
                        it.failuresSinceSuccess == 1 &&
                        it.lastSuccessAt == previousSuccess &&
                        it.errorDetails.isNotEmpty()
                }
            )
        }
    }

    test("records a failed run when the lock cannot be acquired") {
        val failingLock: RenewalLock = mockk { every { tryAcquire() } throws RuntimeException("disk full") }
        every { runRecordStore.load() } returns null
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, failingLock, runRecordStore)

        val result = uc()

        result.shouldNotBeNull()
        result.lockError.shouldNotBeNull()
        verify {
            runRecordStore.save(
                match {
                    it.outcome == RenewalRunOutcome.FAILED &&
                        it.failureReason != null &&
                        it.failuresSinceSuccess == 1
                }
            )
        }
    }

    test("does not record a dry-run") {
        val dir = subDir("rec-dry")
        val file = File(dir, "dry.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns true.right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        uc(dryRun = true)

        verify(exactly = 0) { runRecordStore.save(any()) }
    }

    test("does not record a run skipped because another run holds the lock") {
        val busyLock: RenewalLock = mockk { every { tryAcquire() } returns null }
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, busyLock, runRecordStore)

        val result = uc()

        result.shouldNotBeNull()
        result.alreadyRunning shouldBe true
        verify(exactly = 0) { runRecordStore.save(any()) }
    }
})











