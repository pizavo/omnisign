package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.service.FileRenewalRunRecordStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.SchedulerConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.error.TimestampFailureKind
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.model.result.RenewalAssessment
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.model.result.RenewalReason
import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.port.RenewalAlertSink
import cz.pizavo.omnisign.domain.port.RenewalLock
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
        } returns RenewalAssessment.notNeeded().right()
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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.notNeeded().right()

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

    test("skips signature-less documents as an informational skip, not an error") {
        val dir = subDir("no-signature")
        val file = File(dir, "doc-timestamp-only.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.noSignature().right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.skipped shouldBe 1
        result.renewed shouldBe 0
        result.errors shouldBe 0
        val status = result.jobs.first().files.first()
        status.status shouldBe RenewFileStatus.Status.SKIPPED
        status.message shouldBe "No signature to renew"
        coVerify(exactly = 0) { archivingRepository.extendDocument(any()) }
    }

    test("renews files needing renewal in-place") {
        val dir = subDir("renew")
        val file = File(dir, "renew-expiring.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(
                match { it.inputName == file.name }
            )
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = file.name,
            newSignatureLevel = "PAdES-BASELINE-LTA",
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
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

    test("a document below B-LT is promoted by default") {
        val dir = subDir("promote")
        val file = File(dir, "b-t.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(RenewalReason.BELOW_LT, Clock.System.now() + 365.days).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LTA.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.jobs.first().files.first().reason shouldBe RenewalReason.BELOW_LT
        coVerify {
            archivingRepository.extendDocument(
                match { it.targetLevel == SignatureLevel.PADES_BASELINE_LTA }
            )
        }
    }

    test("a job that opts out of promotion reports the file rather than ignoring it") {
        val dir = subDir("no-promote")
        val file = File(dir, "b-t-left.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(RenewalReason.BELOW_LT, Clock.System.now() + 365.days).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), promoteBelowLt = false)
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.renewed shouldBe 0
        result.errors shouldBe 0
        result.skipped shouldBe 1
        val status = result.jobs.first().files.first()
        status.status shouldBe RenewFileStatus.Status.SKIPPED_BY_POLICY
        status.reason shouldBe RenewalReason.BELOW_LT
        coVerify(exactly = 0) { archivingRepository.extendDocument(any()) }
    }

    test("a refresh is sealed in the same run once the refreshed bytes ask for sealing") {
        val dir = subDir("chain")
        val file = File(dir, "chained.pdf").also { it.writeBytes("original".toByteArray()) }
        val refreshed = "refreshed".toByteArray()
        val sealed = "sealed".toByteArray()
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(RenewalReason.LT_REFRESH_NEEDED).right()
        coEvery {
            archivingRepository.needsArchivalRenewal(match { it.contains(".verify.") }, any())
        } returns RenewalAssessment.needed(RenewalReason.LT_NOT_SEALED).right() andThen
            RenewalAssessment.notNeeded().right()
        coEvery {
            archivingRepository.extendDocument(
                match { it.targetLevel == SignatureLevel.PADES_BASELINE_LT }
            )
        } returns ArchivingResult(
            outputBytes = refreshed,
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LT.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LT,
        ).right()
        coEvery {
            archivingRepository.extendDocument(
                match { it.targetLevel == SignatureLevel.PADES_BASELINE_LTA }
            )
        } returns ArchivingResult(
            outputBytes = sealed,
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LTA.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.errors shouldBe 0
        file.readBytes() shouldBe sealed
        result.jobs.first().files.first().reason shouldBe RenewalReason.LT_NOT_SEALED
    }

    test("a refresh whose sealing fails still keeps the refreshed document") {
        val dir = subDir("chain-degrade")
        val file = File(dir, "degraded.pdf").also { it.writeBytes("original".toByteArray()) }
        val refreshed = "refreshed".toByteArray()
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(RenewalReason.LT_REFRESH_NEEDED).right()
        coEvery {
            archivingRepository.needsArchivalRenewal(match { it.contains(".verify.") }, any())
        } returns RenewalAssessment.needed(RenewalReason.LT_NOT_SEALED).right()
        coEvery {
            archivingRepository.extendDocument(
                match { it.targetLevel == SignatureLevel.PADES_BASELINE_LT }
            )
        } returns ArchivingResult(
            outputBytes = refreshed,
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LT.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LT,
        ).right()
        coEvery {
            archivingRepository.extendDocument(
                match { it.targetLevel == SignatureLevel.PADES_BASELINE_LTA }
            )
        } returns ArchivingError.ExtensionFailed(LocalizableText.Literal("TSA unreachable")).left()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.errors shouldBe 0
        file.readBytes() shouldBe refreshed
        result.jobs.first().files.first().reason shouldBe RenewalReason.LT_REFRESH_NEEDED
    }

    test("stale LT material is refreshed to B-LT rather than sealed at B-LTA") {
        val dir = subDir("refresh")
        val file = File(dir, "stale-lt.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(RenewalReason.LT_REFRESH_NEEDED).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LT.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LT,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        coVerify {
            archivingRepository.extendDocument(
                match { it.targetLevel == SignatureLevel.PADES_BASELINE_LT }
            )
        }
    }

    test("a file past its deadline is terminal — counted apart from errors, run still successful") {
        val dir = subDir("terminal")
        val original = "original".toByteArray()
        val file = File(dir, "expired-signer.pdf").also { it.writeBytes(original) }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.unrecoverable(RenewalReason.BELOW_LT, Clock.System.now() - 30.days).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.unrecoverable shouldBe 1
        result.errors shouldBe 0
        result.renewed shouldBe 0
        result.success shouldBe true
        val status = result.jobs.first().files.first()
        status.status shouldBe RenewFileStatus.Status.UNRECOVERABLE
        status.message.shouldNotBeNull() shouldContain "signing certificate expired"
        file.readBytes() shouldBe original
        coVerify(exactly = 0) { archivingRepository.extendDocument(any()) }
    }

    test("a terminal file keeps the run record successful so staleness can still clear") {
        val dir = subDir("terminal-record")
        File(dir, "expired.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } returns
            RenewalAssessment.unrecoverable(RenewalReason.BELOW_LT, Clock.System.now() - 1.days).right()
        coEvery { runRecordStore.load() } returns null
        val saved = slot<RenewalRunRecord>()
        coEvery { runRecordStore.save(capture(saved)) } returns Unit

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        saved.captured.outcome shouldBe RenewalRunOutcome.SUCCESS
        saved.captured.unrecoverable shouldBe 1
        saved.captured.lastSuccessAt.shouldNotBeNull()
    }

    test("an extension that confirms the deadline has passed is terminal, not a recurring error") {
        val dir = subDir("confirmed-terminal")
        val original = "original".toByteArray()
        val file = File(dir, "confirmed.pdf").also { it.writeBytes(original) }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(
                RenewalReason.LT_REFRESH_NEEDED,
                Clock.System.now() - 30.days,
                deadlineIsFinal = true,
            ).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = "no better".toByteArray(),
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LT.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LT,
            revocationDataMissing = true,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.unrecoverable shouldBe 1
        result.errors shouldBe 0
        result.success shouldBe true
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.UNRECOVERABLE
        file.readBytes() shouldBe original
    }

    test("a stale revocation horizon in the past is not mistaken for a closed window") {
        val dir = subDir("stale-horizon")
        val file = File(dir, "old-crl.pdf").also { it.writeBytes("original".toByteArray()) }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(
                RenewalReason.LT_REFRESH_NEEDED,
                Clock.System.now() - 30.days,
                deadlineIsFinal = false,
            ).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = "no better".toByteArray(),
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LT.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LT,
            revocationDataMissing = true,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.errors shouldBe 1
        result.unrecoverable shouldBe 0
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.ERROR
    }

    test("a seal is not vetoed because re-collecting revocation data failed") {
        val dir = subDir("seal-anyway")
        val file = File(dir, "sound-lt.pdf").also { it.writeBytes("original".toByteArray()) }
        val sealed = "sealed archive".toByteArray()
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(RenewalReason.LT_NOT_SEALED, Clock.System.now() - 7.days).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = sealed,
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LTA.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
            revocationDataMissing = true,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.errors shouldBe 0
        result.unrecoverable shouldBe 0
        file.readBytes() shouldBe sealed
    }

    test("an extension that fails while the deadline is still ahead stays an error") {
        val dir = subDir("still-recoverable")
        val file = File(dir, "recoverable.pdf").also { it.writeBytes("original".toByteArray()) }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns
            RenewalAssessment.needed(RenewalReason.LT_REFRESH_NEEDED, Clock.System.now() + 30.days).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = "no better".toByteArray(),
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LT.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LT,
            revocationDataMissing = true,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.errors shouldBe 1
        result.unrecoverable shouldBe 0
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.ERROR
    }

    test("a terminal file already reported is counted but not logged or notified again") {
        val dir = subDir("terminal-repeat")
        val file = File(dir, "already-reported.pdf").also { it.createNewFile() }
        val log = File(dir, "job.log")
        coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } returns
            RenewalAssessment.unrecoverable(RenewalReason.BELOW_LT, Clock.System.now() - 1.days).right()
        coEvery { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = Clock.System.now() - 1.days,
            outcome = RenewalRunOutcome.SUCCESS,
            unrecoverablePaths = listOf(file.absolutePath),
        )

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), logFile = log.absolutePath)
        val result = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))()

        result.shouldNotBeNull()
        result.unrecoverable shouldBe 1
        result.jobs.first().newlyUnrecoverable shouldBe 0
        log.exists() shouldBe false
    }

    test("an extension that embedded no revocation data is an error and leaves the file untouched") {
        val dir = subDir("no-revocation")
        val original = "original archive".toByteArray()
        val file = File(dir, "no-revocation.pdf").also { it.writeBytes(original) }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = "weaker output".toByteArray(),
            outputName = file.name,
            newSignatureLevel = "PAdES-BASELINE-LTA",
            revocationDataMissing = true,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 3)
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val result = useCaseWith(config)()

        result.shouldNotBeNull()
        result.renewed shouldBe 0
        result.errors shouldBe 1
        result.success shouldBe false
        result.jobs.first().files.first().status shouldBe RenewFileStatus.Status.ERROR
        file.readBytes() shouldBe original
        dir.listFiles { f: File -> f.name.endsWith(".bak") }?.size shouldBe 0
    }

    test("an extension that landed below B-LTA is an error and leaves the file untouched") {
        val dir = subDir("below-lta")
        val original = "original archive".toByteArray()
        val file = File(dir, "below-lta.pdf").also { it.writeBytes(original) }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = "weaker output".toByteArray(),
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_T.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_T,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val result = useCaseWith(config)()

        result.shouldNotBeNull()
        result.renewed shouldBe 0
        result.errors shouldBe 1
        val status = result.jobs.first().files.first()
        status.status shouldBe RenewFileStatus.Status.ERROR
        status.message shouldContain SignatureLevel.PADES_BASELINE_T.name
        file.readBytes() shouldBe original
    }

    test("an extension whose level could not be established is an error, not an assumed success") {
        val dir = subDir("unknown-level")
        val original = "original archive".toByteArray()
        val file = File(dir, "unknown-level.pdf").also { it.writeBytes(original) }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = "unreadable output".toByteArray(),
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LTA.name,
            achievedLevel = null,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val result = useCaseWith(config)()

        result.shouldNotBeNull()
        result.renewed shouldBe 0
        result.errors shouldBe 1
        val status = result.jobs.first().files.first()
        status.status shouldBe RenewFileStatus.Status.ERROR
        status.message shouldContain "could not be established"
        file.readBytes() shouldBe original
    }

    test("an extension that reached B-LTA is written even though the level was read back") {
        val dir = subDir("reached-lta")
        val file = File(dir, "reached-lta.pdf").also { it.writeBytes("original".toByteArray()) }
        val renewed = "renewed archive".toByteArray()
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = renewed,
            outputName = file.name,
            newSignatureLevel = SignatureLevel.PADES_BASELINE_LTA.name,
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val config = baseConfig.copy(renewalJobs = mapOf("j" to job))
        val result = useCaseWith(config)()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.errors shouldBe 0
        file.readBytes() shouldBe renewed
    }

    test("dry-run mode does not modify files") {
        val dir = subDir("dry-run")
        val file = File(dir, "dry-run.pdf").also { it.createNewFile() }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()

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

        coEvery { archivingRepository.needsArchivalRenewal(bad.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.needsArchivalRenewal(good.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == bad.name })
        } returns ArchivingError.ExtensionFailed(LocalizableText.Literal("boom")).left()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == good.name })
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = good.name,
            newSignatureLevel = "PAdES-BASELINE-LTA",
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
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
        } returns ArchivingError.ExtensionFailed(LocalizableText.Literal("check failed")).left()
        coEvery {
            archivingRepository.needsArchivalRenewal(good.absolutePath, any())
        } returns RenewalAssessment.notNeeded().right()

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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, 14) } returns RenewalAssessment.notNeeded().right()

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
        coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } returns RenewalAssessment.notNeeded().right()

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

    test("job with invalid config produces a CONFIG_ERROR status, a job-level error, and preserves notify") {
        val sub = File(tmpDir, "bad-cfg").also { it.mkdirs() }
        File(sub, "doc.pdf").createNewFile()

        val global = baseGlobal.copy(
            disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
        )
        val profile = ProfileConfig(name = "broken")
        val glob = sub.absolutePath.replace('\\', '/') + "/*.pdf"
        val job = RenewalJob(name = "j", globs = listOf(glob), profile = "broken", notify = true)
        val config = AppConfig(
            global = global,
            profiles = mapOf("broken" to profile),
            renewalJobs = mapOf("j" to job),
        )
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.errors shouldBe 1
        val jobResult = result.jobs.first()
        jobResult.files.first().status shouldBe RenewFileStatus.Status.CONFIG_ERROR
        jobResult.errors shouldBe 1
        jobResult.notify shouldBe true
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
        val files = uc.resolveGlobs(listOf(glob)).shouldBeRight()

        files shouldHaveSize 2
        files.all { it.extension == "pdf" } shouldBe true
    }

    test("resolveGlobs handles non-existent root gracefully") {
        val uc = useCaseWith(baseConfig)
        val nonExistent = File(tmpDir, "nonexistent").absolutePath.replace('\\', '/') + "/*.pdf"
        val files = uc.resolveGlobs(listOf(nonExistent)).shouldBeRight()
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
        val files = uc.resolveGlobs(listOf(glob)).shouldBeRight()

        files.map { it.name }.sorted() shouldContainExactly listOf("deep.pdf", "mid.pdf")
    }

    test("resolveGlobs restricts a broad wildcard to PDF files") {
        val sub = File(tmpDir, "broad-glob").also { it.mkdirs() }
        File(sub, "a.pdf").createNewFile()
        File(sub, "notes.txt").createNewFile()
        File(sub, "image.png").createNewFile()

        val uc = useCaseWith(baseConfig)
        val glob = sub.absolutePath.replace('\\', '/') + "/*"
        val files = uc.resolveGlobs(listOf(glob)).shouldBeRight()

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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA", achievedLevel = SignatureLevel.PADES_BASELINE_LTA).right()

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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA", achievedLevel = SignatureLevel.PADES_BASELINE_LTA).right()

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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.needsArchivalRenewal(match { it.contains(".verify.") }, any())
        } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA", achievedLevel = SignatureLevel.PADES_BASELINE_LTA).right()

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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.needsArchivalRenewal(match { it.contains(".verify.") }, any())
        } returns ArchivingError.ExtensionFailed(LocalizableText.Literal("not a valid PDF")).left()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "GARBAGE".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA", achievedLevel = SignatureLevel.PADES_BASELINE_LTA).right()

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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA", achievedLevel = SignatureLevel.PADES_BASELINE_LTA).right()
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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingError.ExtensionFailed(LocalizableText.Literal("tsa down")).left()
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
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        uc(dryRun = true)

        verify(exactly = 0) { runRecordStore.save(any()) }
    }

    test("a lock-skip with no prior run record writes nothing") {
        every { runRecordStore.load() } returns null
        val busyLock: RenewalLock = mockk { every { tryAcquire() } returns null }
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, busyLock, runRecordStore)

        val result = uc()

        result.shouldNotBeNull()
        result.alreadyRunning shouldBe true
        verify(exactly = 0) { runRecordStore.save(any()) }
    }

    test("raises a staleness alert when failures persist past the threshold") {
        val dir = subDir("stale-fire")
        val file = File(dir, "bad.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingError.ExtensionFailed(LocalizableText.Literal("tsa down")).left()
        val longAgo = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = longAgo,
            outcome = RenewalRunOutcome.COMPLETED_WITH_ERRORS,
            failuresSinceSuccess = 30,
            lastSuccessAt = longAgo,
        )

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        val alert = result.shouldNotBeNull().stalenessAlert.shouldNotBeNull()
        (alert.daysWithoutSuccess > 14) shouldBe true
        verify { runRecordStore.save(match { it.lastStaleNotifiedAt != null }) }
    }

    test("warns on the first failed run after a long idle period, counting the idle time") {
        val dir = subDir("stale-idle")
        val file = File(dir, "bad.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingError.ExtensionFailed(LocalizableText.Literal("tsa down")).left()
        val longAgo = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = longAgo,
            outcome = RenewalRunOutcome.SUCCESS,
            lastSuccessAt = longAgo,
            failuresSinceSuccess = 0,
        )

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        val alert = result.shouldNotBeNull().stalenessAlert.shouldNotBeNull()
        (alert.daysWithoutSuccess > 14) shouldBe true
    }

    test("suppresses a repeat staleness alert within the threshold window") {
        val dir = subDir("stale-dedup")
        val file = File(dir, "bad.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingError.ExtensionFailed(LocalizableText.Literal("tsa down")).left()
        val longAgo = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = longAgo,
            outcome = RenewalRunOutcome.COMPLETED_WITH_ERRORS,
            failuresSinceSuccess = 30,
            lastSuccessAt = longAgo,
            lastStaleNotifiedAt = Clock.System.now(),
        )

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull().stalenessAlert.shouldBeNull()
    }

    test("does not raise a staleness alert when the option is disabled") {
        val dir = subDir("stale-off")
        val file = File(dir, "bad.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingError.ExtensionFailed(LocalizableText.Literal("tsa down")).left()
        val longAgo = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = longAgo,
            outcome = RenewalRunOutcome.COMPLETED_WITH_ERRORS,
            failuresSinceSuccess = 30,
            lastSuccessAt = longAgo,
        )

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val config = baseConfig.copy(
            renewalJobs = mapOf("j" to job),
            schedulerConfig = SchedulerConfig(stalenessNotificationEnabled = false),
        )
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull().stalenessAlert.shouldBeNull()
    }

    test("a successful run clears the staleness notification marker") {
        val dir = subDir("stale-reset")
        val file = File(dir, "ok.pdf").also { it.writeText("ORIGINAL") }
        coEvery { archivingRepository.needsArchivalRenewal(file.absolutePath, any()) } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery { archivingRepository.extendDocument(match { it.inputName == file.name }) } returns
            ArchivingResult(outputBytes = "RENEWED".toByteArray(), outputName = file.name, newSignatureLevel = "PAdES-BASELINE-LTA", achievedLevel = SignatureLevel.PADES_BASELINE_LTA).right()
        val longAgo = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = longAgo,
            outcome = RenewalRunOutcome.COMPLETED_WITH_ERRORS,
            failuresSinceSuccess = 30,
            lastSuccessAt = longAgo,
            lastStaleNotifiedAt = longAgo,
        )

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull().stalenessAlert.shouldBeNull()
        verify {
            runRecordStore.save(
                match { it.lastStaleNotifiedAt == null && it.lastSuccessAt != null }
            )
        }
    }

    test("raises a staleness alert when a held lock has blocked renewal past the threshold") {
        val longAgo = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = longAgo,
            outcome = RenewalRunOutcome.SUCCESS,
            lastSuccessAt = longAgo,
        )
        coEvery { configRepository.getCurrentConfig() } returns baseConfig
        val busyLock: RenewalLock = mockk { every { tryAcquire() } returns null }
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, busyLock, runRecordStore)

        val result = uc()

        result.shouldNotBeNull().alreadyRunning shouldBe true
        val alert = result.stalenessAlert.shouldNotBeNull()
        (alert.daysWithoutSuccess > 14) shouldBe true
        verify { runRecordStore.save(match { it.lastStaleNotifiedAt != null }) }
    }

    test("a lock-skip warns without disturbing the recorded last-run status") {
        val longAgo = Instant.fromEpochSeconds(1_000_000)
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = longAgo,
            outcome = RenewalRunOutcome.SUCCESS,
            lastSuccessAt = longAgo,
        )
        coEvery { configRepository.getCurrentConfig() } returns baseConfig
        val busyLock: RenewalLock = mockk { every { tryAcquire() } returns null }
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, busyLock, runRecordStore)

        val result = uc()

        result.shouldNotBeNull().stalenessAlert.shouldNotBeNull()
        verify {
            runRecordStore.save(
                match {
                    it.lastStaleNotifiedAt != null &&
                        it.outcome == RenewalRunOutcome.SUCCESS &&
                        it.lastRunAt == longAgo
                }
            )
        }
    }

    test("marks the run as in flight before the batch and clears the marker once it finishes") {
        val saved = mutableListOf<RenewalRunRecord>()
        every { runRecordStore.save(capture(saved)) } returns Unit
        val uc = useCaseWith(baseConfig)

        uc()

        saved.first().runStartedAt.shouldNotBeNull()
        saved.last().runStartedAt.shouldBeNull()
    }

    test("a dry-run leaves no in-flight marker behind") {
        val uc = useCaseWith(baseConfig)

        uc(dryRun = true)

        verify(exactly = 0) { runRecordStore.save(any()) }
    }

    test("a run that never finished is recorded as interrupted and counted as a failure") {
        val died = Clock.System.now() - 1.days
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = died - 1.days,
            outcome = RenewalRunOutcome.SUCCESS,
            lastSuccessAt = died - 1.days,
            failuresSinceSuccess = 2,
            runStartedAt = died,
        )
        val saved = mutableListOf<RenewalRunRecord>()
        every { runRecordStore.save(capture(saved)) } returns Unit
        val uc = useCaseWith(baseConfig)

        uc()

        val interrupted = saved.first()
        interrupted.outcome shouldBe RenewalRunOutcome.INTERRUPTED
        interrupted.lastRunAt shouldBe died
        interrupted.failuresSinceSuccess shouldBe 3
        interrupted.runStartedAt.shouldNotBeNull()
    }

    test("an interrupted run carries the last success and the reported terminal files forward") {
        val died = Clock.System.now() - 1.days
        val succeeded = died - 5.days
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = succeeded,
            outcome = RenewalRunOutcome.SUCCESS,
            unrecoverablePaths = listOf("/archive/lost.pdf"),
            lastSuccessAt = succeeded,
            runStartedAt = died,
        )
        val saved = mutableListOf<RenewalRunRecord>()
        every { runRecordStore.save(capture(saved)) } returns Unit
        val uc = useCaseWith(baseConfig)

        uc()

        val interrupted = saved.first()
        interrupted.lastSuccessAt shouldBe succeeded
        interrupted.unrecoverablePaths shouldContainExactly listOf("/archive/lost.pdf")
    }

    test("resolveGlobs reports an unparseable pattern instead of throwing it at the whole batch") {
        val dir = subDir("bad-glob")
        val uc = useCaseWith(baseConfig)
        val glob = dir.absolutePath.replace('\\', '/') + "/[.pdf"

        uc.resolveGlobs(listOf(glob)).shouldBeLeft().shouldContain("not a valid glob pattern")
    }

    test("a malformed glob fails its own job and leaves the other jobs running") {
        val goodDir = subDir("glob-good")
        val badDir = subDir("glob-bad")
        val file = File(goodDir, "doc.pdf").also { it.createNewFile() }
        coEvery {
            archivingRepository.needsArchivalRenewal(file.absolutePath, any())
        } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == file.name })
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = file.name,
            newSignatureLevel = "PAdES-BASELINE-LTA",
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
        ).right()

        val broken = RenewalJob(name = "broken", globs = listOf(badDir.absolutePath.replace('\\', '/') + "/[.pdf"))
        val healthy = RenewalJob(name = "healthy", globs = listOf(globDir(goodDir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("broken" to broken, "healthy" to healthy)))

        val result = uc()

        result.shouldNotBeNull()
        result.errors shouldBe 1
        result.renewed shouldBe 1
        val brokenJob = result.jobs.single { it.name == "broken" }
        brokenJob.files.single().status shouldBe RenewFileStatus.Status.CONFIG_ERROR
        result.jobs.single { it.name == "healthy" }.files.single().status shouldBe RenewFileStatus.Status.RENEWED
    }

    test("a document that fails with a JVM Error is one error row, not a dead batch") {
        val dir = subDir("error-guard")
        val boom = File(dir, "boom.pdf").also { it.createNewFile() }
        val fine = File(dir, "fine.pdf").also { it.createNewFile() }
        coEvery {
            archivingRepository.needsArchivalRenewal(boom.absolutePath, any())
        } throws OutOfMemoryError("simulated heap exhaustion")
        coEvery {
            archivingRepository.needsArchivalRenewal(fine.absolutePath, any())
        } returns RenewalAssessment.notNeeded().right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        result.errors shouldBe 1
        result.skipped shouldBe 1
        result.jobs.single().files
            .single { it.path == boom.absolutePath }.status shouldBe RenewFileStatus.Status.ERROR
    }

    test("an unreachable timestamp server is dialled only until the breaker trips") {
        val dir = subDir("tsa-breaker")
        val names = listOf("a", "b", "c", "d", "e")
        names.forEach { name ->
            val file = File(dir, "$name.pdf").also { it.createNewFile() }
            coEvery {
                archivingRepository.needsArchivalRenewal(file.absolutePath, any())
            } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        }
        coEvery { archivingRepository.extendDocument(any()) } returns ArchivingError.TimestampFailed(
            text = LocalizableText.Literal("Timestamp server could not be reached."),
            kind = TimestampFailureKind.UNREACHABLE,
        ).left()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        coVerify(exactly = RenewBatchUseCase.TSA_FAILURE_LIMIT) {
            archivingRepository.extendDocument(any())
        }
        result.errors shouldBe names.size
        result.skipped shouldBe 0
        result.success shouldBe false
        result.jobs.single().files.last().message.shouldNotBeNull() shouldContain "stopped calling it"
    }

    test("documents the breaker skips are errors, never skips, so the run cannot claim success") {
        val dir = subDir("tsa-breaker-status")
        listOf("a", "b", "c", "d").forEach { name ->
            val file = File(dir, "$name.pdf").also { it.createNewFile() }
            coEvery {
                archivingRepository.needsArchivalRenewal(file.absolutePath, any())
            } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        }
        coEvery { archivingRepository.extendDocument(any()) } returns ArchivingError.TimestampFailed(
            text = LocalizableText.Literal("Timestamp server could not be reached."),
            kind = TimestampFailureKind.UNREACHABLE,
        ).left()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        result.jobs.single().files.forEach { it.status shouldBe RenewFileStatus.Status.ERROR }
    }

    test("a timestamp server that answers again resets the breaker") {
        val dir = subDir("tsa-recovers")
        listOf("a", "b", "c", "d", "e").forEach { name ->
            val file = File(dir, "$name.pdf").also { it.createNewFile() }
            coEvery {
                archivingRepository.needsArchivalRenewal(file.absolutePath, any())
            } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        }
        coEvery {
            archivingRepository.extendDocument(match { it.inputName != "c.pdf" })
        } returns ArchivingError.TimestampFailed(
            text = LocalizableText.Literal("Timestamp server could not be reached."),
            kind = TimestampFailureKind.UNREACHABLE,
        ).left()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == "c.pdf" })
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = "c.pdf",
            newSignatureLevel = "PAdES-BASELINE-LTA",
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        coVerify(exactly = 5) { archivingRepository.extendDocument(any()) }
        result.renewed shouldBe 1
        result.errors shouldBe 4
    }

    test("a timestamp server answering with something that is not a token also trips the breaker") {
        val dir = subDir("tsa-garbage")
        listOf("a", "b", "c", "d", "e").forEach { name ->
            val file = File(dir, "$name.pdf").also { it.createNewFile() }
            coEvery {
                archivingRepository.needsArchivalRenewal(file.absolutePath, any())
            } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        }
        coEvery { archivingRepository.extendDocument(any()) } returns ArchivingError.TimestampFailed(
            text = LocalizableText.Literal("Timestamp server returned a malformed response."),
            kind = TimestampFailureKind.MALFORMED_RESPONSE,
        ).left()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        coVerify(exactly = RenewBatchUseCase.TSA_FAILURE_LIMIT) { archivingRepository.extendDocument(any()) }
        result.errors shouldBe 5
        result.jobs.single().files.last().message.shouldNotBeNull() shouldContain "did not return a valid timestamp"
    }

    test("the breaker's reason distinguishes an unreachable server from a misconfigured one") {
        val dir = subDir("tsa-kind-message")
        listOf("a", "b", "c", "d").forEach { name ->
            val file = File(dir, "$name.pdf").also { it.createNewFile() }
            coEvery {
                archivingRepository.needsArchivalRenewal(file.absolutePath, any())
            } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        }
        coEvery { archivingRepository.extendDocument(any()) } returns ArchivingError.TimestampFailed(
            text = LocalizableText.Literal("Timestamp server could not be reached."),
            kind = TimestampFailureKind.UNREACHABLE,
        ).left()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        result.jobs.single().files.last().message.shouldNotBeNull() shouldContain "could not be reached"
    }

    test("a timestamp server that answers and rejects the request does not trip the breaker") {
        val dir = subDir("tsa-rejects")
        listOf("a", "b", "c", "d", "e").forEach { name ->
            val file = File(dir, "$name.pdf").also { it.createNewFile() }
            coEvery {
                archivingRepository.needsArchivalRenewal(file.absolutePath, any())
            } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        }
        coEvery { archivingRepository.extendDocument(any()) } returns ArchivingError.TimestampFailed(
            text = LocalizableText.Literal("Timestamp server rejected the request: badRequest"),
            kind = TimestampFailureKind.REJECTED,
        ).left()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        coVerify(exactly = 5) { archivingRepository.extendDocument(any()) }
        result.errors shouldBe 5
    }

    test("a job pointing at a healthy timestamp server is unaffected by another job's dead one") {
        val deadDir = subDir("tsa-dead")
        val liveDir = subDir("tsa-live")
        listOf("a", "b", "c", "d").forEach { name ->
            val file = File(deadDir, "$name.pdf").also { it.createNewFile() }
            coEvery {
                archivingRepository.needsArchivalRenewal(file.absolutePath, any())
            } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        }
        val healthy = File(liveDir, "live.pdf").also { it.createNewFile() }
        coEvery {
            archivingRepository.needsArchivalRenewal(healthy.absolutePath, any())
        } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName != "live.pdf" })
        } returns ArchivingError.TimestampFailed(
            text = LocalizableText.Literal("Timestamp server could not be reached."),
            kind = TimestampFailureKind.UNREACHABLE,
        ).left()
        coEvery {
            archivingRepository.extendDocument(match { it.inputName == "live.pdf" })
        } returns ArchivingResult(
            outputBytes = ByteArray(0),
            outputName = "live.pdf",
            newSignatureLevel = "PAdES-BASELINE-LTA",
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
        ).right()

        val config = baseConfig.copy(
            profiles = mapOf(
                "other" to ProfileConfig(
                    name = "other",
                    timestampServer = TimestampServerConfig(url = "https://other-tsa.example.com"),
                )
            ),
            renewalJobs = mapOf(
                "dead" to RenewalJob(name = "dead", globs = listOf(globDir(deadDir))),
                "live" to RenewalJob(name = "live", globs = listOf(globDir(liveDir)), profile = "other", backupRetention = 0),
            ),
        )
        val uc = useCaseWith(config)
        val result = uc()

        result.shouldNotBeNull()
        result.renewed shouldBe 1
        result.jobs.single { it.name == "live" }.files.single().status shouldBe RenewFileStatus.Status.RENEWED
    }

    test("a JVM Error raised while extending is one error row, not a dead batch") {
        val dir = subDir("error-extend")
        val boom = File(dir, "boom.pdf").also { it.createNewFile() }
        val fine = File(dir, "fine.pdf").also { it.createNewFile() }
        coEvery {
            archivingRepository.needsArchivalRenewal(boom.absolutePath, any())
        } returns RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
        coEvery {
            archivingRepository.needsArchivalRenewal(fine.absolutePath, any())
        } returns RenewalAssessment.notNeeded().right()
        coEvery {
            archivingRepository.extendDocument(any())
        } throws OutOfMemoryError("simulated heap exhaustion")

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        val result = uc()

        result.shouldNotBeNull()
        result.errors shouldBe 1
        result.skipped shouldBe 1
        boom.readBytes().size shouldBe 0
    }

    test("cancelling a run is not turned into a document error") {
        val dir = subDir("cancelled")
        val file = File(dir, "doc.pdf").also { it.createNewFile() }
        coEvery {
            archivingRepository.needsArchivalRenewal(file.absolutePath, any())
        } throws CancellationException("run cancelled")

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))

        shouldThrow<CancellationException> { uc() }
    }

    test("a run aborted mid-batch leaves a marker the next run turns into a recorded failure") {
        val dir = subDir("abort-marker")
        val file = File(dir, "doc.pdf").also { it.createNewFile() }
        val store = FileRenewalRunRecordStore(File(tmpDir, "abort-run.json").toPath())
        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)))
        coEvery { configRepository.getCurrentConfig() } returns
            baseConfig.copy(renewalJobs = mapOf("j" to job))
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, grantingLock, store)

        coEvery {
            archivingRepository.needsArchivalRenewal(file.absolutePath, any())
        } throws CancellationException("run cancelled")
        shouldThrow<CancellationException> { uc() }

        store.load().shouldNotBeNull().runStartedAt.shouldNotBeNull()

        coEvery {
            archivingRepository.needsArchivalRenewal(file.absolutePath, any())
        } returns ArchivingError.renewalCheckFailed(details = "still broken").left()
        uc()

        val record = store.load().shouldNotBeNull()
        record.runStartedAt.shouldBeNull()
        record.failuresSinceSuccess shouldBe 2
    }

    test("interrupted runs in a row are counted, and the alert fires once the streak is long enough") {
        val alertSink: RenewalAlertSink = mockk(relaxed = true)
        val saved = mutableListOf<RenewalRunRecord>()
        every { runRecordStore.save(capture(saved)) } returns Unit
        val died = Clock.System.now() - 1.days
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, grantingLock, runRecordStore, alertSink)
        coEvery { configRepository.getCurrentConfig() } returns baseConfig

        (1..RenewBatchUseCase.INTERRUPTION_ALERT_THRESHOLD).forEach { streak ->
            every { runRecordStore.load() } returns RenewalRunRecord(
                lastRunAt = died,
                outcome = RenewalRunOutcome.INTERRUPTED,
                lastSuccessAt = died - 10.days,
                runStartedAt = died,
                consecutiveInterruptions = streak - 1,
            )
            saved.clear()
            uc()
            saved.first().consecutiveInterruptions shouldBe streak
        }

        verify(exactly = 1) {
            alertSink.runsKeepBeingInterrupted(RenewBatchUseCase.INTERRUPTION_ALERT_THRESHOLD)
        }
    }

    test("a single interruption is not announced") {
        val alertSink: RenewalAlertSink = mockk(relaxed = true)
        val died = Clock.System.now() - 1.days
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = died,
            outcome = RenewalRunOutcome.SUCCESS,
            lastSuccessAt = died,
            runStartedAt = died,
        )
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, grantingLock, runRecordStore, alertSink)
        coEvery { configRepository.getCurrentConfig() } returns baseConfig

        uc()

        verify(exactly = 0) { alertSink.runsKeepBeingInterrupted(any()) }
    }

    test("a run that finishes clears the interruption streak") {
        val died = Clock.System.now() - 1.days
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = died,
            outcome = RenewalRunOutcome.INTERRUPTED,
            lastSuccessAt = died - 10.days,
            consecutiveInterruptions = 2,
        )
        val saved = mutableListOf<RenewalRunRecord>()
        every { runRecordStore.save(capture(saved)) } returns Unit
        val uc = useCaseWith(baseConfig)

        uc()

        saved.last().consecutiveInterruptions shouldBe 0
    }

    test("the run is not broken by an alert sink that throws") {
        val alertSink: RenewalAlertSink = mockk {
            every { runsKeepBeingInterrupted(any()) } throws IllegalStateException("notifier down")
        }
        val died = Clock.System.now() - 1.days
        every { runRecordStore.load() } returns RenewalRunRecord(
            lastRunAt = died,
            outcome = RenewalRunOutcome.INTERRUPTED,
            lastSuccessAt = died - 10.days,
            runStartedAt = died,
            consecutiveInterruptions = RenewBatchUseCase.INTERRUPTION_ALERT_THRESHOLD - 1,
        )
        val uc = RenewBatchUseCase(checkRenewal, extend, configRepository, grantingLock, runRecordStore, alertSink)
        coEvery { configRepository.getCurrentConfig() } returns baseConfig

        uc().shouldNotBeNull()
    }

    test("the file staged to verify a renewal is invisible to the job's own glob") {
        val dir = subDir("verify-invisible")
        val file = File(dir, "doc.pdf").also { it.writeText("ORIGINAL") }
        val inspected = mutableListOf<String>()
        coEvery { archivingRepository.needsArchivalRenewal(any(), any()) } answers {
            val inspectedPath = firstArg<String>()
            inspected += inspectedPath
            if (inspectedPath == file.absolutePath) {
                RenewalAssessment.needed(RenewalReason.TIMESTAMP_EXPIRING).right()
            } else {
                RenewalAssessment.notNeeded().right()
            }
        }
        coEvery { archivingRepository.extendDocument(any()) } returns ArchivingResult(
            outputBytes = "RENEWED".toByteArray(),
            outputName = file.name,
            newSignatureLevel = "PAdES-BASELINE-LTA",
            achievedLevel = SignatureLevel.PADES_BASELINE_LTA,
        ).right()

        val job = RenewalJob(name = "j", globs = listOf(globDir(dir)), backupRetention = 0)
        val uc = useCaseWith(baseConfig.copy(renewalJobs = mapOf("j" to job)))
        uc()

        val staged = inspected.single { it.contains(".verify.") }
        staged.shouldEndWith(".tmp")
        File(staged).parentFile shouldBe dir.absoluteFile
        uc.resolveGlobs(listOf(globDir(dir))).shouldBeRight()
            .map { it.absolutePath } shouldNotContain staged
    }
})











