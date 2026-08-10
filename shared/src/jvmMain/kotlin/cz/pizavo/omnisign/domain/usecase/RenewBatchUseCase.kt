package cz.pizavo.omnisign.domain.usecase

import arrow.core.Either
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.SchedulerConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.model.result.RenewJobResult
import cz.pizavo.omnisign.domain.model.result.RenewalAssessment
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.model.result.RenewalReason
import cz.pizavo.omnisign.domain.model.result.RenewalRunError
import cz.pizavo.omnisign.domain.model.result.RenewalRunJobSummary
import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import cz.pizavo.omnisign.domain.model.result.StalenessAlert
import cz.pizavo.omnisign.domain.port.RenewalLock
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo
import kotlin.io.path.writeBytes
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private val logger = KotlinLogging.logger {}

/**
 * Executes all configured renewal jobs (or a single named job), checking each
 * matched PDF against its renewal buffer and re-timestamping it in place — always to
 * PAdES B-LTA — when its outermost document timestamp, or a signature timestamp not yet
 * sealed by one, is nearing the expiry of its signing certificate or of one of its
 * algorithms. Because the target is always B-LTA, a matched B-T or B-LT document is
 * promoted to B-LTA as part of renewal.
 *
 * A file is only overwritten once the extension is known to have delivered. Two independent checks
 * guard that, and either one leaves the original untouched and logs the reason rather than reporting
 * a renewal that did not happen: the cause, when the extension reports
 * [cz.pizavo.omnisign.domain.model.result.ArchivingResult.revocationDataMissing], and the effect,
 * when [cz.pizavo.omnisign.domain.model.result.ArchivingResult.achievedLevel] read back out of the
 * produced bytes is below B-LTA. Overwriting an archive with a weaker document would not just lose
 * ground — the fresh timestamp resets the renewal clock, hiding the gap until it too ages.
 *
 * An achieved level that could not be established at all counts as a failure too, for the same
 * reason [cz.pizavo.omnisign.domain.model.result.RenewalNeed] treats an unresolvable timestamp
 * certificate as undeterminable rather than safe: an archival job may only replace a file it has
 * confirmed is stronger, and "unknown" is not a confirmation.
 *
 * One failure is recorded as terminal rather than as an error: an extension that could not obtain
 * usable revocation data for a document whose deadline
 * ([cz.pizavo.omnisign.domain.model.result.RenewalAssessment.dueAt]) has already passed. The
 * assessment deliberately stops short of calling such a document hopeless, because it runs offline
 * and cannot see the trusted-list metadata that might still rescue it; the extension attempt does
 * load that metadata, so its failure is the confirmation. Recording it as an error instead would
 * leave every future run reporting itself as failing over a file nothing can repair.
 *
 * This use case encapsulates the core batch logic shared by the CLI `renew`
 * command and the desktop app's headless renewal mode. Presentation concerns
 * (console output, JSON formatting) remain in the caller.
 *
 * @param checkRenewalUseCase Checks whether a single document needs renewal.
 * @param extendDocumentUseCase Extends a document to the target PAdES level.
 * @param configRepository Provides the current [AppConfig] with renewal jobs.
 * @param renewalLock Host-wide guard that prevents two renewal runs from re-timestamping the
 *   same documents at once.
 * @param runRecordStore Persists a summary of each run so the CLI and desktop can surface last-run
 *   status (when it ran, whether it succeeded, and any failures since the last success).
 */
class RenewBatchUseCase(
    private val checkRenewalUseCase: CheckArchivalRenewalUseCase,
    private val extendDocumentUseCase: ExtendDocumentUseCase,
    private val configRepository: ConfigRepository,
    private val renewalLock: RenewalLock,
    private val runRecordStore: RenewalRunRecordStore,
) {

    /**
     * Run renewal jobs and return an aggregated [RenewBatchResult].
     *
     * The run is guarded by a host-wide [RenewalLock]: when another renewal process already holds
     * the lock, this call re-timestamps nothing and returns a result with
     * [RenewBatchResult.alreadyRunning] set, so two schedulers — or a manual run overlapping the
     * scheduled one — can never re-timestamp the same documents concurrently; it still evaluates
     * staleness, so a lock that stays held for too long is surfaced rather than silently skipped. If
     * the lock cannot be established at all, the run is **not** attempted and a result with
     * [RenewBatchResult.lockError] is returned.
     *
     * @param jobName Optional name of a single job to execute. When `null`, all
     *   configured jobs are processed.
     * @param dryRun When `true`, files that need renewal are reported but not
     *   modified.
     * @return A [RenewBatchResult] summarising every job and file outcome, or a result with
     *   [RenewBatchResult.alreadyRunning] when another run holds the lock, or a result with
     *   [RenewBatchResult.lockError] when the lock could not be acquired, or `null` when the requested
     *   [jobName] does not exist. A completed run or a lock-skipped run additionally carries a
     *   [RenewBatchResult.stalenessAlert] when renewal has now gone too long without a success.
     */
    suspend operator fun invoke(
        jobName: String? = null,
        dryRun: Boolean = false,
    ): RenewBatchResult? {
        val lock = try {
            renewalLock.tryAcquire()
        } catch (e: Exception) {
            val result = RenewBatchResult(lockError = e.message ?: "the renewal lock could not be acquired")
            recordRun(result, emitStaleness = false)
            return result
        }
        if (lock == null) return RenewBatchResult(alreadyRunning = true, stalenessAlert = recordSkippedRun())
        return try {
            val result = runBatch(jobName, dryRun) ?: return null
            if (dryRun) result else result.copy(stalenessAlert = recordRun(result, emitStaleness = true))
        } finally {
            lock.close()
        }
    }

    /**
     * Persist a [RenewalRunRecord] summarising [result], carrying the last-success timestamp forward
     * and counting consecutive failures since it. Never called for dry-runs or for runs skipped
     * because another run held the lock. A persistence failure is logged and otherwise ignored, so
     * status bookkeeping can never break a run.
     *
     * When [emitStaleness] is `true` — a run that actually executed, as opposed to a lock failure
     * reported separately — and renewal has now gone [SchedulerConfig.stalenessThresholdDays] without
     * a success, a [StalenessAlert] is returned (via [decideStaleness]) and
     * [RenewalRunRecord.lastStaleNotifiedAt] is stamped so the alert re-fires at most once per
     * threshold window. Staleness is wall-clock time since [RenewalRunRecord.lastSuccessAt] —
     * including time the machine was off, since the renewal buffer expires in real time — yet a
     * powered-off machine never trips it, because staleness is only evaluated when a run actually
     * executes and a successful run resets the clock. Lock-skipped runs evaluate it too, via
     * [recordSkippedRun].
     *
     * @param result The run to record.
     * @param emitStaleness Whether to evaluate and return the staleness alert for this run.
     * @return A [StalenessAlert] when one should be shown for this run, otherwise `null`.
     */
    private suspend fun recordRun(result: RenewBatchResult, emitStaleness: Boolean): StalenessAlert? {
        return try {
            val now = Clock.System.now()
            val previous = runRecordStore.load()
            val outcome = when {
                result.lockError != null -> RenewalRunOutcome.FAILED
                result.errors > 0 -> RenewalRunOutcome.COMPLETED_WITH_ERRORS
                else -> RenewalRunOutcome.SUCCESS
            }
            val succeeded = outcome == RenewalRunOutcome.SUCCESS
            val lastSuccessAt = if (succeeded) now else previous?.lastSuccessAt
            val previousNotifiedAt = previous?.lastStaleNotifiedAt
            val staleAlert = if (!succeeded && emitStaleness) {
                decideStaleness(now, lastSuccessAt, previousNotifiedAt)
            } else {
                null
            }
            val nextNotifiedAt = when {
                succeeded -> null
                staleAlert != null -> now
                else -> previousNotifiedAt
            }

            val errorDetails = result.jobs.flatMap { job ->
                job.files
                    .filter { it.status == RenewFileStatus.Status.ERROR || it.status == RenewFileStatus.Status.CONFIG_ERROR }
                    .map { RenewalRunError(path = it.path, message = it.message ?: "unknown error") }
            }
            val warnings = result.jobs.flatMap { it.files }.flatMap { it.warnings }.distinct()
            val jobs = result.jobs.map {
                RenewalRunJobSummary(
                    name = it.name,
                    renewed = it.renewed,
                    errors = it.errors,
                    unrecoverable = it.unrecoverable,
                )
            }
            runRecordStore.save(
                RenewalRunRecord(
                    lastRunAt = now,
                    outcome = outcome,
                    checked = result.checked,
                    renewed = result.renewed,
                    skipped = result.skipped,
                    errors = result.errors,
                    unrecoverable = result.unrecoverable,
                    unrecoverablePaths = result.unrecoverablePaths,
                    failureReason = result.lockError,
                    errorDetails = errorDetails,
                    warnings = warnings,
                    jobs = jobs,
                    lastSuccessAt = lastSuccessAt,
                    failuresSinceSuccess = if (succeeded) 0 else (previous?.failuresSinceSuccess ?: 0) + 1,
                    lastStaleNotifiedAt = nextNotifiedAt,
                )
            )
            staleAlert
        } catch (e: Exception) {
            logger.warn(e) { "Could not persist the renewal run record" }
            null
        }
    }

    /**
     * Decide whether renewal is now stale enough to warn, given when it [lastSuccessAt] last succeeded
     * (or `null` if it never has) and when the staleness alert was last raised ([previousNotifiedAt]).
     * Staleness is wall-clock time since the last success, so a long absence counts toward it. Reads
     * the live [SchedulerConfig.stalenessNotificationEnabled] / [SchedulerConfig.stalenessThresholdDays];
     * a config-read failure falls back to defaults so bookkeeping never breaks a run.
     *
     * @return The [StalenessAlert] to raise, or `null` when renewal is not (yet) stale or was already
     *   warned about within the current threshold window.
     */
    private suspend fun decideStaleness(
        now: Instant,
        lastSuccessAt: Instant?,
        previousNotifiedAt: Instant?,
    ): StalenessAlert? {
        if (lastSuccessAt == null) return null
        val scheduler = runCatching { configRepository.getCurrentConfig().schedulerConfig }
            .getOrDefault(SchedulerConfig())
        val thresholdDays = scheduler.stalenessThresholdDays
        val sinceSuccess = now - lastSuccessAt
        val staleEnough = scheduler.stalenessNotificationEnabled &&
            thresholdDays >= 1 &&
            sinceSuccess >= thresholdDays.days
        val notifiedRecently = previousNotifiedAt != null &&
            now - previousNotifiedAt < thresholdDays.days
        return if (staleEnough && !notifiedRecently) {
            StalenessAlert(daysWithoutSuccess = sinceSuccess.inWholeDays.toInt())
        } else {
            null
        }
    }

    /**
     * Evaluate staleness for a scheduled run that was skipped because another run already held the
     * lock, so a lock that stays held is surfaced rather than silently swallowed. Measures wall-clock
     * time since [RenewalRunRecord.lastSuccessAt] just like a completed run; when that now exceeds
     * [SchedulerConfig.stalenessThresholdDays] it returns a [StalenessAlert] and stamps
     * [RenewalRunRecord.lastStaleNotifiedAt] (the only field a skip ever writes, leaving the recorded
     * last-run status untouched). With no prior record there is nothing to measure against, so it does
     * nothing. A persistence failure is logged and otherwise ignored, so a skipped run can never break.
     *
     * @return A [StalenessAlert] when renewal has now gone too long without a success, otherwise `null`.
     */
    private suspend fun recordSkippedRun(): StalenessAlert? {
        return try {
            val previous = runRecordStore.load() ?: return null
            val now = Clock.System.now()
            val staleAlert = decideStaleness(now, previous.lastSuccessAt, previous.lastStaleNotifiedAt)
            if (staleAlert != null) {
                runRecordStore.save(previous.copy(lastStaleNotifiedAt = now))
            }
            staleAlert
        } catch (e: Exception) {
            logger.warn(e) { "Could not evaluate renewal staleness for a skipped run" }
            null
        }
    }

    /**
     * Execute the configured renewal jobs while the [RenewalLock] is held; see [invoke] for the
     * parameters and return value.
     */
    private suspend fun runBatch(jobName: String?, dryRun: Boolean): RenewBatchResult? {
        val appConfig = configRepository.getCurrentConfig()

        val jobsToRun = if (jobName != null) {
            val job = appConfig.renewalJobs[jobName] ?: return null
            mapOf(jobName to job)
        } else {
            appConfig.renewalJobs
        }

        var totalChecked = 0
        var totalRenewed = 0
        var totalSkipped = 0
        var totalErrors = 0
        var totalUnrecoverable = 0

        val previouslyTerminal = runCatching { runRecordStore.load()?.unrecoverablePaths?.toSet() }
            .getOrNull()
            .orEmpty()
        val terminalPaths = mutableListOf<String>()
        val jobResults = mutableListOf<RenewJobResult>()

        for ((_, job) in jobsToRun) {
            val files = resolveGlobs(job.globs, job.logFile)
            if (files.isEmpty()) {
                jobResults.add(RenewJobResult(name = job.name, notify = job.notify))
                continue
            }

            val resolvedConfigResult = resolveJobConfig(appConfig, job)
            if (resolvedConfigResult.isLeft()) {
                val error = resolvedConfigResult.leftOrNull()!!
                logger.warn { "Renewal job '${job.name}' configuration error — ${error.message}" }
                totalErrors++
                jobResults.add(
                    RenewJobResult(
                        name = job.name,
                        files = listOf(
                            RenewFileStatus(
                                path = "",
                                status = RenewFileStatus.Status.CONFIG_ERROR,
                                message = error.message,
                            )
                        ),
                        errors = 1,
                        notify = job.notify,
                    )
                )
                continue
            }
            val resolvedConfig = resolvedConfigResult.getOrNull()!!

            var jobRenewed = 0
            var jobErrors = 0
            var jobUnrecoverable = 0
            var jobNewlyUnrecoverable = 0
            val fileStatuses = mutableListOf<RenewFileStatus>()

            /**
             * Record [path] as past its preservation deadline: counted apart from errors, logged and
             * announced only the first time, and never retried into a permanent run failure.
             */
            fun recordTerminal(path: String, message: String, reason: RenewalReason?) {
                totalUnrecoverable++
                jobUnrecoverable++
                terminalPaths += path
                if (path !in previouslyTerminal) {
                    jobNewlyUnrecoverable++
                    logger.warn { "[TERMINAL] $path — $message" }
                    appendLog(job.logFile, "[TERMINAL] $path — $message")
                }
                fileStatuses.add(
                    RenewFileStatus(
                        path = path,
                        status = RenewFileStatus.Status.UNRECOVERABLE,
                        message = message,
                        reason = reason,
                    )
                )
            }

            for (file in files) {
                totalChecked++
                val path = file.absolutePath

                checkRenewalUseCase(path, job.renewalBufferDays).fold(
                    ifLeft = { error ->
                        totalErrors++
                        jobErrors++
                        logFileError(job.logFile, path, error.message)
                        fileStatuses.add(
                            RenewFileStatus(path = path, status = RenewFileStatus.Status.ERROR, message = error.message)
                        )
                    },
                    ifRight = { assessment ->
                        when (assessment.need) {
                            RenewalNeed.NOT_NEEDED -> {
                                totalSkipped++
                                appendLog(job.logFile, "[SKIP]  $path — protection is current, nothing due yet")
                                fileStatuses.add(
                                    RenewFileStatus(path = path, status = RenewFileStatus.Status.SKIPPED)
                                )
                                return@fold
                            }
                            RenewalNeed.NO_SIGNATURE -> {
                                totalSkipped++
                                logger.info { "[SKIP] $path — no signature to renew; renewal applies to signed documents" }
                                appendLog(job.logFile, "[SKIP]  $path — no signature to renew")
                                fileStatuses.add(RenewFileStatus(path = path, status = RenewFileStatus.Status.SKIPPED, message = "No signature to renew"))
                                return@fold
                            }
                            RenewalNeed.UNRECOVERABLE -> {
                                recordTerminal(path, unrecoverableMessage(assessment), assessment.reason)
                                return@fold
                            }
                            RenewalNeed.NEEDED -> { }
                        }

                        if (assessment.reason == RenewalReason.BELOW_LT && !job.promoteBelowLt) {
                            totalSkipped++
                            val message = "below B-LT and this job does not promote such documents"
                            appendLog(job.logFile, "[SKIP]  $path — $message")
                            fileStatuses.add(
                                RenewFileStatus(
                                    path = path,
                                    status = RenewFileStatus.Status.SKIPPED_BY_POLICY,
                                    message = message,
                                    reason = assessment.reason,
                                )
                            )
                            return@fold
                        }

                        val targetLevel = targetLevelFor(assessment.reason)

                        if (dryRun) {
                            totalRenewed++
                            jobRenewed++
                            appendLog(job.logFile, "[DRY-RUN] $path — would be re-timestamped")
                            fileStatuses.add(RenewFileStatus(path = path, status = RenewFileStatus.Status.DRY_RUN))
                            return@fold
                        }

                        val writabilityError = probeDirectoryWritable(file)
                        if (writabilityError != null) {
                            totalErrors++
                            jobErrors++
                            logFileError(job.logFile, path, writabilityError)
                            fileStatuses.add(
                                RenewFileStatus(
                                    path = path,
                                    status = RenewFileStatus.Status.ERROR,
                                    message = writabilityError,
                                )
                            )
                            return@fold
                        }

                        val inputBytes = runCatching { file.readBytes() }.getOrNull()
                        if (inputBytes == null) {
                            totalErrors++
                            jobErrors++
                            logFileError(job.logFile, path, "renewal failed: could not read file")
                            fileStatuses.add(
                                RenewFileStatus(
                                    path = path,
                                    status = RenewFileStatus.Status.ERROR,
                                    message = "Could not read file",
                                )
                            )
                            return@fold
                        }

                        extendDocumentUseCase(
                            ArchivingParameters(
                                inputBytes = inputBytes,
                                inputName = file.name,
                                targetLevel = targetLevel,
                                resolvedConfig = resolvedConfig,
                            )
                        ).fold(
                            ifLeft = { error ->
                                totalErrors++
                                jobErrors++
                                logFileError(job.logFile, path, "renewal failed: ${error.message}")
                                fileStatuses.add(
                                    RenewFileStatus(
                                        path = path,
                                        status = RenewFileStatus.Status.ERROR,
                                        message = error.message,
                                    )
                                )
                            },
                            ifRight = { result ->
                                if (result.revocationDataMissing) {
                                    val deadline = assessment.dueAt
                                    if (deadline != null && deadline <= Clock.System.now()) {
                                        recordTerminal(
                                            path,
                                            "the attempt confirmed no usable revocation data can be obtained: the " +
                                                "deadline passed $deadline and the original was left unchanged",
                                            assessment.reason,
                                        )
                                        return@fold
                                    }
                                    totalErrors++
                                    jobErrors++
                                    val reason = "renewal could not embed the revocation data " +
                                        "${targetLevel.name} requires; the original was left unchanged"
                                    logFileError(job.logFile, path, reason)
                                    fileStatuses.add(
                                        RenewFileStatus(
                                            path = path,
                                            status = RenewFileStatus.Status.ERROR,
                                            message = reason,
                                            warnings = result.warnings,
                                        )
                                    )
                                    return@fold
                                }
                                val achieved = result.achievedLevel
                                if (achieved == null || achieved < targetLevel) {
                                    totalErrors++
                                    jobErrors++
                                    val reason = if (achieved == null) {
                                        "the level of the renewed document could not be established, so it was not " +
                                            "confirmed to have reached ${targetLevel.name}; " +
                                            "the original was left unchanged"
                                    } else {
                                        "renewal produced a ${achieved.name} document rather than " +
                                            "${targetLevel.name}; the original was left unchanged"
                                    }
                                    logFileError(job.logFile, path, reason)
                                    fileStatuses.add(
                                        RenewFileStatus(
                                            path = path,
                                            status = RenewFileStatus.Status.ERROR,
                                            message = reason,
                                            warnings = result.warnings,
                                        )
                                    )
                                    return@fold
                                }
                                val validationError = verifyRenewedOutput(
                                    file, result.outputBytes, job.renewalBufferDays, assessment.reason,
                                )
                                if (validationError != null) {
                                    totalErrors++
                                    jobErrors++
                                    logFileError(job.logFile, path, validationError)
                                    fileStatuses.add(
                                        RenewFileStatus(
                                            path = path,
                                            status = RenewFileStatus.Status.ERROR,
                                            message = validationError,
                                        )
                                    )
                                    return@fold
                                }
                                if (job.backupRetention > 0) {
                                    val backupError = runCatching { writeBackup(file, inputBytes) }.exceptionOrNull()
                                    if (backupError != null) {
                                        totalErrors++
                                        jobErrors++
                                        logFileError(job.logFile, path, "backup failed: ${backupError.message}")
                                        fileStatuses.add(
                                            RenewFileStatus(
                                                path = path,
                                                status = RenewFileStatus.Status.ERROR,
                                                message = "Backup failed: ${backupError.message}",
                                            )
                                        )
                                        return@fold
                                    }
                                }
                                val writeError = runCatching { writeAtomically(file, result.outputBytes) }.exceptionOrNull()
                                if (writeError != null) {
                                    totalErrors++
                                    jobErrors++
                                    logFileError(job.logFile, path, "renewal failed: ${writeError.message}")
                                    fileStatuses.add(
                                        RenewFileStatus(
                                            path = path,
                                            status = RenewFileStatus.Status.ERROR,
                                            message = writeError.message,
                                        )
                                    )
                                    return@fold
                                }
                                if (job.backupRetention > 0) {
                                    runCatching { pruneBackups(file, job.backupRetention) }
                                        .onFailure { logger.warn(it) { "Could not prune old backups for $path" } }
                                }
                                totalRenewed++
                                jobRenewed++
                                appendLog(job.logFile, "${renewedTag(assessment.reason)} $path — now ${achieved.name}")
                                result.rawWarnings.forEach { w ->
                                    appendLog(job.logFile, "[WARN] $path — $w")
                                }
                                fileStatuses.add(
                                    RenewFileStatus(
                                        path = path,
                                        status = RenewFileStatus.Status.RENEWED,
                                        warnings = result.warnings,
                                        reason = assessment.reason,
                                    )
                                )
                            }
                        )
                    }
                )
            }

            jobResults.add(
                RenewJobResult(
                    name = job.name,
                    files = fileStatuses,
                    renewed = jobRenewed,
                    errors = jobErrors,
                    unrecoverable = jobUnrecoverable,
                    newlyUnrecoverable = jobNewlyUnrecoverable,
                    notify = job.notify,
                )
            )
        }

        return RenewBatchResult(
            checked = totalChecked,
            renewed = totalRenewed,
            skipped = totalSkipped,
            errors = totalErrors,
            unrecoverable = totalUnrecoverable,
            unrecoverablePaths = terminalPaths,
            dryRun = dryRun,
            jobs = jobResults,
        )
    }

    /**
     * The level a document has to be extended to in order to close the gap [reason] names.
     *
     * [RenewalReason.LT_REFRESH_NEEDED] deliberately targets B-LT rather than B-LTA: its revocation
     * data does not cover the signature, and an archival timestamp would seal that gap rather than
     * close it. Refreshing first leaves the document at B-LT with data that does cover it, and the
     * next run seals it as [RenewalReason.LT_NOT_SEALED]. Every other reason is closed by reaching
     * B-LTA, which embeds revocation data and anchors it in one operation.
     */
    private fun targetLevelFor(reason: RenewalReason?): SignatureLevel =
        if (reason == RenewalReason.LT_REFRESH_NEEDED) {
            SignatureLevel.PADES_BASELINE_LT
        } else {
            SignatureLevel.PADES_BASELINE_LTA
        }

    /**
     * The job-log tag for a completed step, so the log distinguishes a document that was brought up
     * to a level it had never reached from one whose existing protection was renewed.
     */
    private fun renewedTag(reason: RenewalReason?): String = when (reason) {
        RenewalReason.BELOW_LT, RenewalReason.LT_NOT_SEALED -> "[PROMOTED]"
        RenewalReason.LT_REFRESH_NEEDED -> "[REFRESHED]"
        else -> "[RENEWED]"
    }

    /**
     * A human-readable explanation of why nothing can be done for a document any more, naming the
     * deadline that has passed so the operator can see how long ago the chance was lost.
     */
    private fun unrecoverableMessage(assessment: RenewalAssessment): String {
        val deadline = assessment.dueAt?.let { " (expired $it)" } ?: ""
        return when (assessment.reason) {
            RenewalReason.BELOW_LT ->
                "the signing certificate expired$deadline before revocation data was embedded, so this " +
                    "document can no longer reach ${SignatureLevel.PADES_BASELINE_LT.name}"

            else -> "the deadline for the required preservation step has passed$deadline"
        }
    }

    /**
     * Expand a list of glob patterns to a deduplicated, sorted list of existing
     * PDF files.
     *
     * Both forward-slash and backslash separators are accepted in [globs].
     * Matching uses the relative tail of the glob pattern against the relative
     * path from the root directory, avoiding platform-specific backslash
     * escaping issues with [java.nio.file.PathMatcher] on Windows.
     *
     * Wildcard matches are restricted to `.pdf` files (case-insensitive), since renewal handles PDFs
     * only; any non-PDF files a wildcard happens to match are counted and logged rather than silently
     * dropped. A literal (wildcard-free) path is taken as-is.
     *
     * The walk is fault-tolerant: an unreadable directory (or one that becomes
     * inaccessible mid-walk) is skipped rather than aborting the whole batch, so a
     * single permission problem cannot stop every job from renewing. Each skipped path is
     * logged to the application log and appended to [logFile] when one is configured, so the
     * gap is never silent.
     */
    internal fun resolveGlobs(globs: List<String>, logFile: String? = null): List<File> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<File>()

        for (glob in globs) {
            val normalised = glob.replace('\\', '/')
            val wildcardIndex = normalised.indexOfFirst { it == '*' || it == '?' || it == '{' || it == '[' }

            if (wildcardIndex == -1) {
                val file = File(glob).absoluteFile
                if (file.isFile && seen.add(file.absolutePath)) {
                    results.add(file)
                }
                continue
            }

            val prefix = normalised.substring(0, wildcardIndex)
            val lastSlash = prefix.lastIndexOf('/')
            val rootStr = if (lastSlash == -1) "." else normalised.substring(0, lastSlash)
            val rootPath = Paths.get(rootStr).toAbsolutePath().normalize()
            if (!Files.isDirectory(rootPath)) continue

            val tail = normalised.substring(lastSlash + 1)
            val matcher = rootPath.fileSystem.getPathMatcher("glob:$tail")

            val matched = mutableListOf<Path>()
            var nonPdfCount = 0
            try {
                Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile && matcher.matches(rootPath.relativize(file))) {
                            if (file.fileName.toString().endsWith(".pdf", ignoreCase = true)) {
                                matched.add(file)
                            } else {
                                nonPdfCount++
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        logSkippedGlobPath(file, exc, logFile)
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (e: IOException) {
                logSkippedGlobPath(rootPath, e, logFile)
            }
            matched.sorted().forEach { path ->
                val abs = path.toAbsolutePath().normalize().toString()
                if (seen.add(abs)) results.add(path.toFile())
            }
            if (nonPdfCount > 0) {
                logger.info { "Renewal glob '$glob' matched $nonPdfCount non-PDF file(s); renewal handles PDFs only, ignoring them" }
                appendLog(logFile, "[SKIP] $glob — ignored $nonPdfCount non-PDF file(s) (PDFs only)")
            }
        }
        return results
    }

    /**
     * Log a glob-walk path that had to be skipped because it could not be read — once to the
     * application log and, when configured, to the job's [logFile] — so an unreadable directory
     * never silently excludes files from renewal.
     */
    private fun logSkippedGlobPath(path: Path, cause: Exception, logFile: String?) {
        logger.warn(cause) { "Renewal could not access $path — skipping it" }
        appendLog(logFile, "[WARN] skipped unreadable path: $path — ${cause.message}")
    }

    /**
     * Atomically replace [target]'s contents with [bytes].
     *
     * The bytes are written and flushed to a temporary file in [target]'s own directory and
     * then moved over [target] with [StandardCopyOption.ATOMIC_MOVE]. The existing file is never
     * truncated in place, so an interrupted run — a crash, a kill, power loss, or a full disk —
     * can never leave a half-written or empty file where a valid archive used to be: a reader
     * sees either the original document or the fully renewed one. The temporary file shares the
     * target's directory so the move stays on one filesystem (a cross-device move cannot be
     * atomic) and is removed again if any step fails.
     *
     * @param target The file whose contents are replaced.
     * @param bytes The complete renewed document.
     */
    internal fun writeAtomically(target: File, bytes: ByteArray) {
        val targetPath = target.toPath()
        val directory = requireNotNull(targetPath.toAbsolutePath().parent) {
            "Cannot resolve a parent directory for $target"
        }
        val tempFile = createTempFile(directory, ".${target.name}.", ".tmp")
        try {
            FileChannel.open(tempFile, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            try {
                tempFile.moveTo(targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                tempFile.moveTo(targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.deleteIfExists()
        }
    }

    /**
     * Write a timestamped backup of [originalBytes] beside [file] as `<name>.<utc>.bak`, so a bad
     * renewal can be rolled back. The name embeds a UTC instant in basic ISO form (no `:`, so it is
     * a valid filename on Windows). Written via [writeAtomically], so the backup is always complete
     * or absent.
     *
     * @param file The document about to be renewed.
     * @param originalBytes The pre-renewal content to preserve.
     * @return The backup file that was written.
     */
    internal fun writeBackup(file: File, originalBytes: ByteArray): File {
        val timestamp = BACKUP_TIMESTAMP.format(Clock.System.now().toJavaInstant())
        val backupFile = File(file.absoluteFile.parentFile, "${file.name}.$timestamp.bak")
        writeAtomically(backupFile, originalBytes)
        return backupFile
    }

    /**
     * Delete the oldest timestamped backups of [file], keeping only the newest [retention]. Only the
     * backups this class writes — named `<file.name>.<utc-timestamp>.bak` — are considered, so an
     * unrelated `.bak` file is never touched; basic-ISO timestamps sort chronologically by name.
     *
     * @param file The renewed document whose sibling backups should be pruned.
     * @param retention The number of newest backups to keep.
     */
    internal fun pruneBackups(file: File, retention: Int) {
        val pattern = Regex("^${Regex.escape(file.name)}\\.\\d{8}T\\d{6}Z\\.bak$")
        val backups = file.absoluteFile.parentFile
            ?.listFiles { candidate -> candidate.isFile && pattern.matches(candidate.name) }
            ?.sortedBy { it.name }
            ?: return
        backups.dropLast(retention).forEach { it.delete() }
    }

    /**
     * Confirm [file]'s directory accepts writes before the expensive timestamp call, by creating and
     * immediately deleting a probe file there. Returns an error message when the directory cannot be
     * written (so the renewal is skipped without a wasted TSA round-trip), or `null` when it is
     * writable. The probe file is always removed — even if anything fails — so none is left behind.
     *
     * @param file The document about to be renewed.
     * @return `null` when the directory is writable, or a human-readable reason it is not.
     */
    internal fun probeDirectoryWritable(file: File): String? {
        val directory = file.absoluteFile.toPath().parent
            ?: return "could not resolve a directory for $file"
        var probe: Path? = null
        return try {
            probe = createTempFile(directory, ".${file.name}.probe.", ".tmp")
            null
        } catch (e: Exception) {
            "cannot write to $directory (check permissions and free space): ${e.message}"
        } finally {
            probe?.let { p ->
                runCatching { p.deleteIfExists() }
                    .onFailure { logger.warn(it) { "Could not delete writability-probe file $p" } }
            }
        }
    }

    /**
     * Re-run the coverage-aware renewal check on the just-produced [outputBytes] before they
     * overwrite [file]. A sound step must parse and must have *moved the document on*: the check is
     * that it no longer reports the same [actedOn] reason, not that it reports nothing at all.
     *
     * The distinction matters once a step can be partial. Refreshing stale revocation data leaves a
     * document at B-LT that legitimately still needs sealing, and demanding "needs nothing" would
     * reject that progress and preserve the weaker original for ever. Re-reporting the very same
     * reason, on the other hand, means the extension changed nothing that mattered.
     *
     * The bytes are checked through a short-lived verify file in [file]'s own directory — the same
     * path that decides renewal in the first place — which is always deleted afterwards.
     *
     * @param actedOn The reason the renewal set out to address.
     * @return `null` when the renewed document is sound, or a human-readable reason it is not.
     */
    private suspend fun verifyRenewedOutput(
        file: File,
        outputBytes: ByteArray,
        bufferDays: Int,
        actedOn: RenewalReason?,
    ): String? {
        val directory = file.absoluteFile.toPath().parent
            ?: return "could not resolve a directory to validate the renewed document"
        val verifyFile = try {
            createTempFile(directory, ".${file.name}.verify.", ".pdf")
        } catch (e: Exception) {
            return "could not stage the renewed document for validation: ${e.message}"
        }
        return try {
            verifyFile.writeBytes(outputBytes)
            checkRenewalUseCase(verifyFile.absolutePathString(), bufferDays).fold(
                ifLeft = { "the renewed document failed validation: ${it.message}" },
                ifRight = { assessment ->
                    if (assessment.need == RenewalNeed.NEEDED && assessment.reason == actedOn) {
                        "the renewed document still reports the same need (${actedOn?.name})"
                    } else {
                        null
                    }
                },
            )
        } catch (e: Exception) {
            "could not validate the renewed document: ${e.message}"
        } finally {
            verifyFile.deleteIfExists()
        }
    }

    /**
     * Build a [ResolvedConfig] for a renewal job, honoring the job's optional
     * profile override.
     */
    private fun resolveJobConfig(
        appConfig: AppConfig,
        job: RenewalJob,
    ): Either<ConfigurationError.InvalidConfiguration, ResolvedConfig> {
        val profileName = job.profile ?: appConfig.activeProfile
        val profileConfig = profileName?.let { appConfig.profiles[it] }
        return ResolvedConfig.resolve(
            global = appConfig.global,
            profile = profileConfig,
            operationOverrides = null,
        )
    }

    /**
     * Append a single structured log line to [logFile], prefixed with an
     * ISO-8601 timestamp. A write failure is reported to the application log and otherwise
     * ignored, so a broken job log file never aborts a renewal run.
     */
    private fun appendLog(logFile: String?, message: String) {
        if (logFile == null) return
        try {
            File(logFile).apply { parentFile?.mkdirs() }
                .appendText("${Clock.System.now()} $message\n")
        } catch (e: Exception) {
            logger.warn(e) { "Could not write to renewal log file $logFile" }
        }
    }

    /**
     * Record a per-file renewal failure both to the application log — so it is captured even when
     * no job [logFile] is configured — and to the job's [logFile] audit trail, mirroring
     * [logSkippedGlobPath] for the error case. The caller still updates its counters and the
     * [RenewFileStatus] list.
     *
     * @param logFile The job's optional audit-log file path.
     * @param path The document that failed renewal.
     * @param reason A human-readable description of the failure.
     */
    private fun logFileError(logFile: String?, path: String, reason: String) {
        logger.warn { "Renewal failed for $path — $reason" }
        appendLog(logFile, "[ERROR] $path — $reason")
    }

    companion object {
        /**
         * Formats the UTC instant embedded in a backup file name, in basic ISO-8601
         * (e.g. `20260614T020000Z`) so the name is a valid filename on Windows (no `:`).
         */
        private val BACKUP_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}






