package cz.pizavo.omnisign.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.SchedulerConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.TimestampFailureKind
import cz.pizavo.omnisign.domain.model.error.isServerWide
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
import cz.pizavo.omnisign.domain.port.RenewalAlertSink
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
import kotlin.coroutines.cancellation.CancellationException
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
 * One failure is recorded as terminal rather than as an error: an extension whose purpose was to
 * obtain revocation data could not obtain any, and the document's deadline was a final one
 * ([cz.pizavo.omnisign.domain.model.result.RenewalAssessment.deadlineIsFinal]) that has already
 * passed. The assessment deliberately stops short of calling such a document hopeless, because it
 * runs offline and cannot see the trusted-list metadata that might still rescue it; the extension
 * attempt does load that metadata, so its failure is the confirmation. Recording it as an error
 * instead would leave every future run reporting itself as failing over a file nothing can repair.
 *
 * Both qualifiers are load-bearing. Keying on the deadline merely being in the past would treat an
 * ordinary stale revocation horizon as a closed window and drop a recoverable file from every future
 * run; letting the warning veto a seal would refuse to anchor a document whose existing material is
 * sound, because re-collection failed for a certificate that has since expired.
 *
 * A run stops calling a timestamp server that has failed [TSA_FAILURE_LIMIT] consecutive documents
 * with a [TimestampFailureKind] that [isServerWide], and fails the remaining documents immediately.
 * Reaching the TSA is the last step of an extension, so without this every document would first parse
 * the archive and collect revocation data, then wait out the connection timeout, before failing.
 *
 * Only failures that would repeat count — the server was unreachable, or answered with something that
 * is not a timestamp token. An RFC 3161 rejection concerns one request and never trips the breaker,
 * and any completed extension resets the count. Skipped documents are recorded as errors rather than
 * skips, because a run of skips would report success, reset the last-success timestamp, and hold off
 * the staleness alert over documents nothing renewed.
 *
 * No single document can end the run. Expected failures are already values rather than throws, and
 * the per-document step adds a `Throwable` guard on top, because the blanket `catch (e: Exception)`
 * blocks in the DSS layer do not cover a JVM `Error`; DSS holds whole documents in memory, so an
 * oversized PDF can raise one. Unguarded, that would abort the remaining jobs and skip the run record,
 * leaving the scheduler looking healthy. Continuing after an `OutOfMemoryError` is only partly sound,
 * since the heap may stay degraded — the gain is that later failures are reported rather than silent.
 * A [CancellationException] is re-thrown, since cancelling a run is not a document-level failure.
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
 * @param alertSink Receives the one alert that cannot travel in the result, because the run carrying
 *   it is being killed. `null` where nothing can notify, leaving the condition recorded but silent.
 */
class RenewBatchUseCase(
    private val checkRenewalUseCase: CheckArchivalRenewalUseCase,
    private val extendDocumentUseCase: ExtendDocumentUseCase,
    private val configRepository: ConfigRepository,
    private val renewalLock: RenewalLock,
    private val runRecordStore: RenewalRunRecordStore,
    private val alertSink: RenewalAlertSink? = null,
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
     * Before the batch starts, the run is marked as in flight (see [markRunStarted]) and any marker a
     * previous run left behind is written up as an interrupted run. A dry-run neither marks nor
     * records anything.
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
            if (!dryRun) markRunStarted()
            val result = runBatch(jobName, dryRun)
            when {
                result == null -> {
                    if (!dryRun) clearRunMarker()
                    null
                }
                dryRun -> result
                else -> result.copy(stalenessAlert = recordRun(result, emitStaleness = true))
            }
        } finally {
            lock.close()
        }
    }

    /**
     * Stamp [RenewalRunRecord.runStartedAt] before the batch begins, and record any marker a previous
     * run left behind as an interrupted run.
     *
     * A marker that is already set means the previous run began and never reached [recordRun], so it
     * was killed. This is the one place that can be concluded safely, because the renewal lock is held
     * here and no other run can be in flight to explain the marker.
     *
     * The dead run is recorded as [RenewalRunOutcome.INTERRUPTED] with its counts zeroed, since nothing
     * is known about how far it got, and it adds one to the failures since the last success. That
     * count is what eventually lets the staleness alert fire on a machine interrupted every night.
     *
     * Three fields are carried forward: [RenewalRunRecord.lastSuccessAt] is the staleness clock,
     * [RenewalRunRecord.unrecoverablePaths] keeps already-reported terminal documents from being
     * announced again, and [RenewalRunRecord.lastStaleNotifiedAt] keeps the alert from re-firing
     * inside its window.
     *
     * Once [INTERRUPTION_ALERT_THRESHOLD] runs in a row have died, [alertSink] is told here and not
     * through the returned result, because a run that is about to be killed never returns one. The
     * staleness alert cannot serve this case for the same reason: it is only ever evaluated by a run
     * that finishes.
     *
     * A persistence failure is logged and otherwise ignored, so status bookkeeping can never break a
     * run.
     */
    private suspend fun markRunStarted() {
        try {
            val now = Clock.System.now()
            val previous = runRecordStore.load()
            val deadRunStartedAt = previous?.runStartedAt
            if (previous == null) {
                runRecordStore.save(
                    RenewalRunRecord(
                        lastRunAt = now,
                        outcome = RenewalRunOutcome.INTERRUPTED,
                        runStartedAt = now,
                    )
                )
                return
            }
            if (deadRunStartedAt == null) {
                runRecordStore.save(previous.copy(runStartedAt = now))
                return
            }
            val streak = previous.consecutiveInterruptions + 1
            logger.warn {
                "The renewal run started $deadRunStartedAt never finished — recording it as " +
                    "interrupted ($streak in a row)"
            }
            runRecordStore.save(
                previous.copy(
                    lastRunAt = deadRunStartedAt,
                    outcome = RenewalRunOutcome.INTERRUPTED,
                    checked = 0,
                    renewed = 0,
                    skipped = 0,
                    errors = 0,
                    unrecoverable = 0,
                    failureReason = "the run started $deadRunStartedAt did not finish",
                    errorDetails = emptyList(),
                    warnings = emptyList(),
                    jobs = emptyList(),
                    failuresSinceSuccess = previous.failuresSinceSuccess + 1,
                    runStartedAt = now,
                    consecutiveInterruptions = streak,
                )
            )
            if (streak == INTERRUPTION_ALERT_THRESHOLD) {
                runCatching { alertSink?.runsKeepBeingInterrupted(streak) }
                    .onFailure { logger.warn(it) { "Could not raise the interrupted-run alert" } }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not mark the renewal run as started" }
        }
    }

    /**
     * Clear [RenewalRunRecord.runStartedAt] for a run that ended without producing a result, which
     * happens when the requested job does not exist. Without this the next run would read the marker
     * as a run that was killed. A persistence failure is logged and otherwise ignored.
     */
    private suspend fun clearRunMarker() {
        try {
            val previous = runRecordStore.load() ?: return
            if (previous.runStartedAt != null) runRecordStore.save(previous.copy(runStartedAt = null))
        } catch (e: Exception) {
            logger.warn(e) { "Could not clear the renewal run marker" }
        }
    }

    /**
     * Persist a [RenewalRunRecord] summarising [result], carrying the last-success timestamp forward
     * and counting consecutive failures since it. Never called for dry-runs or for runs skipped
     * because another run held the lock. A persistence failure is logged and otherwise ignored, so
     * status bookkeeping can never break a run.
     *
     * Writing this record also clears [RenewalRunRecord.runStartedAt]. An interrupted run never
     * reaches this point, so the marker [markRunStarted] set survives only when the run died.
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
                    runStartedAt = null,
                    consecutiveInterruptions = 0,
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

        /**
         * Consecutive server-wide timestamp failures, per TSA URL, within this run. Keyed by URL so
         * that jobs sharing a timestamp server share the verdict, while a job pointing at a different
         * server is unaffected. Any completed extension resets the entry.
         */
        val tsaServerFailures = mutableMapOf<String, Int>()

        /**
         * The kind of the most recent server-wide failure, per TSA URL. Kept so the reason recorded
         * against each skipped document can name what went wrong; the breaker itself treats both kinds
         * alike, but an operator needs to tell them apart.
         */
        val tsaFailureKinds = mutableMapOf<String, TimestampFailureKind>()

        for ((_, job) in jobsToRun) {
            val globResult = resolveGlobs(job.globs, job.logFile)
            if (globResult.isLeft()) {
                totalErrors++
                jobResults.add(configErrorResult(job, globResult.leftOrNull()!!))
                continue
            }
            val files = globResult.getOrNull()!!
            if (files.isEmpty()) {
                jobResults.add(RenewJobResult(name = job.name, notify = job.notify))
                continue
            }

            val resolvedConfigResult = resolveJobConfig(appConfig, job)
            if (resolvedConfigResult.isLeft()) {
                val error = resolvedConfigResult.leftOrNull()!!
                logger.warn { "Renewal job '${job.name}' configuration error — ${error.message}" }
                totalErrors++
                jobResults.add(configErrorResult(job, error.message))
                continue
            }
            val resolvedConfig = resolvedConfigResult.getOrNull()!!
            val tsaUrl = resolvedConfig.timestampServer?.url

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

            /**
             * The whole per-document step: assess [file] and, when something is due, extend it and
             * write it back. Updates the enclosing job's counters and file statuses in place.
             *
             * @param file The matched document.
             * @param path [file]'s absolute path. Passed in because the caller resolves it anyway, to
             *   report a failure of this function itself.
             */
            suspend fun processFile(file: File, path: String) {
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

                        if (tsaUrl != null && (tsaServerFailures[tsaUrl] ?: 0) >= TSA_FAILURE_LIMIT) {
                            totalErrors++
                            jobErrors++
                            val reason = "the timestamp server $tsaUrl " +
                                "${tsaFailureDescription(tsaFailureKinds[tsaUrl])} on the last " +
                                "$TSA_FAILURE_LIMIT attempts, so this run stopped calling it; " +
                                "the original was left unchanged and the next run will try again"
                            logFileError(job.logFile, path, reason)
                            fileStatuses.add(
                                RenewFileStatus(
                                    path = path,
                                    status = RenewFileStatus.Status.ERROR,
                                    message = reason,
                                )
                            )
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
                                val failureKind = (error as? ArchivingError.TimestampFailed)?.kind
                                if (tsaUrl != null && failureKind?.isServerWide == true) {
                                    tsaServerFailures[tsaUrl] = (tsaServerFailures[tsaUrl] ?: 0) + 1
                                    tsaFailureKinds[tsaUrl] = failureKind
                                }
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
                                if (tsaUrl != null) tsaServerFailures[tsaUrl] = 0
                                if (result.revocationDataMissing && obtainsMaterial(assessment.reason)) {
                                    val deadline = assessment.dueAt
                                    if (assessment.deadlineIsFinal && deadline != null && deadline <= Clock.System.now()) {
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
                                val verified = verifyRenewedOutput(
                                    file, result.outputBytes, job.renewalBufferDays, assessment.reason,
                                )
                                if (verified.isLeft()) {
                                    val validationError = verified.leftOrNull()!!
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

                                val sealedBytes = if (
                                    assessment.reason == RenewalReason.LT_REFRESH_NEEDED &&
                                    verified.getOrNull()?.reason == RenewalReason.LT_NOT_SEALED
                                ) {
                                    sealAfterRefresh(file, result.outputBytes, resolvedConfig, job.renewalBufferDays)
                                } else {
                                    null
                                }
                                val finalBytes = sealedBytes ?: result.outputBytes
                                val completedReason = if (sealedBytes != null) {
                                    RenewalReason.LT_NOT_SEALED
                                } else {
                                    assessment.reason
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
                                val writeError = runCatching { writeAtomically(file, finalBytes) }.exceptionOrNull()
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
                                val reachedLevel = if (sealedBytes != null) {
                                    SignatureLevel.PADES_BASELINE_LTA
                                } else {
                                    achieved
                                }
                                appendLog(job.logFile, "${renewedTag(completedReason)} $path — now ${reachedLevel.name}")
                                result.rawWarnings.forEach { w ->
                                    appendLog(job.logFile, "[WARN] $path — $w")
                                }
                                fileStatuses.add(
                                    RenewFileStatus(
                                        path = path,
                                        status = RenewFileStatus.Status.RENEWED,
                                        warnings = result.warnings,
                                        reason = completedReason,
                                    )
                                )
                            }
                        )
                    }
                )
            }

            for (file in files) {
                totalChecked++
                val path = file.absolutePath
                try {
                    processFile(file, path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    totalErrors++
                    jobErrors++
                    val reason = "renewal failed unexpectedly: ${e.message ?: e::class.simpleName}"
                    logFileError(job.logFile, path, reason)
                    fileStatuses.add(
                        RenewFileStatus(
                            path = path,
                            status = RenewFileStatus.Status.ERROR,
                            message = reason,
                        )
                    )
                }
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
     * How to describe a TSA the breaker has given up on. An unreachable server is an outage to wait
     * out; one that answers without producing a timestamp is usually a URL pointing somewhere that
     * does not speak RFC 3161, which needs a configuration change instead.
     *
     * @param kind The kind of the most recent failure, or `null` if none was recorded.
     */
    private fun tsaFailureDescription(kind: TimestampFailureKind?): String = when (kind) {
        TimestampFailureKind.UNREACHABLE -> "could not be reached"
        TimestampFailureKind.MALFORMED_RESPONSE -> "did not return a valid timestamp"
        else -> "failed"
    }

    /**
     * The single-row [RenewJobResult] reporting [job] as unrunnable because of the configuration
     * problem [message] describes — an unresolvable profile, or a glob the platform cannot parse.
     *
     * Counted as one error so that a job which never ran cannot leave the run reporting success, which
     * would reset the last-success timestamp and hold off the staleness alert.
     */
    private fun configErrorResult(job: RenewalJob, message: String): RenewJobResult =
        RenewJobResult(
            name = job.name,
            files = listOf(
                RenewFileStatus(
                    path = "",
                    status = RenewFileStatus.Status.CONFIG_ERROR,
                    message = message,
                )
            ),
            errors = 1,
            notify = job.notify,
        )

    /**
     * The level a document has to be extended to in order to close the gap [reason] names.
     *
     * [RenewalReason.LT_REFRESH_NEEDED] deliberately targets B-LT rather than B-LTA: its revocation
     * data does not cover the signature, and an archival timestamp would seal that gap rather than
     * close it. Refreshing first leaves the document at B-LT with data that does cover it, and the
     * next run seals it as [RenewalReason.LT_NOT_SEALED]. Every other reason is closed by reaching
     * B-LTA, which embeds revocation data and anchors it in one operation.
     */
    /**
     * Whether the step [reason] calls for is one whose *purpose* is to obtain revocation data, so
     * that failing to obtain any means the step achieved nothing.
     *
     * For [RenewalReason.LT_NOT_SEALED] and the two aging reasons the document already holds usable
     * material and the step is to seal or re-timestamp it. Those operations re-collect revocation
     * data as a side effect, and that collection can fail — an expired signing certificate makes
     * every freshly fetched response unusable — without the operation itself having failed. Letting
     * that warning veto the write would refuse to seal exactly the documents whose existing material
     * most needs anchoring before it ages out.
     */
    private fun obtainsMaterial(reason: RenewalReason?): Boolean =
        reason == RenewalReason.BELOW_LT || reason == RenewalReason.LT_REFRESH_NEEDED

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
     *
     * A pattern the platform cannot parse at all is reported as a configuration error for the job,
     * which the caller surfaces like an unresolvable profile. Both the root ([Paths.get], which
     * rejects characters illegal in a path) and the wildcard tail
     * ([java.nio.file.FileSystem.getPathMatcher], which rejects an unclosed `[` or `{`) raise
     * unchecked exceptions, while add-time validation inspects only the root. Unguarded, such a
     * pattern would escape the whole batch, skipping every remaining job and writing no run record, so
     * a single typo would stop renewal indefinitely and leave no record of why.
     *
     * @return the matched files, or a description of the pattern that could not be parsed.
     */
    internal fun resolveGlobs(globs: List<String>, logFile: String? = null): Either<String, List<File>> {
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
            val rootPath = try {
                Paths.get(rootStr).toAbsolutePath().normalize()
            } catch (e: Exception) {
                return malformedGlob(glob, "its directory '$rootStr' is not a valid path", e, logFile)
            }
            if (!Files.isDirectory(rootPath)) continue

            val tail = normalised.substring(lastSlash + 1)
            val matcher = try {
                rootPath.fileSystem.getPathMatcher("glob:$tail")
            } catch (e: Exception) {
                return malformedGlob(glob, "'$tail' is not a valid glob pattern", e, logFile)
            }

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
        return results.right()
    }

    /**
     * Report [glob] as unparseable, logging it to the application log and to the job's [logFile]
     * before returning the message [resolveGlobs] fails with.
     *
     * @param glob The pattern as the user configured it.
     * @param reason Which half of the pattern the platform rejected.
     * @param cause The exception the path or matcher API raised.
     * @param logFile The job's optional audit-log file path.
     */
    private fun malformedGlob(
        glob: String,
        reason: String,
        cause: Exception,
        logFile: String?,
    ): Either<String, List<File>> {
        val message = "renewal glob '$glob' cannot be used: $reason"
        logger.warn(cause) { message }
        appendLog(logFile, "[ERROR] $message")
        return message.left()
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
     * path that decides renewal in the first place — which is always deleted afterwards. That
     * directory is used rather than the system temp directory because it is already known to be
     * writable ([probeDirectoryWritable] has just checked it) and to have room for these bytes, which
     * are about to be written there anyway. Neither holds for a shared temp location that may be small
     * or memory-backed.
     *
     * The suffix is deliberately not `.pdf`. A run killed before the `finally` below — a crash, a
     * reboot, power loss — leaves the verify file behind, and [resolveGlobs] admits a wildcard match
     * only when it ends in `.pdf` (see its `nonPdfCount` branch). That check is what keeps every other
     * temporary file this class writes invisible to the next run. A `.pdf` verify file would step
     * around it and be picked up as a document of the job: counted, re-validated, and possibly
     * re-timestamped on every run from then on.
     *
     * @param actedOn The reason the renewal set out to address.
     * @return `null` when the renewed document is sound, or a human-readable reason it is not.
     */
    private suspend fun verifyRenewedOutput(
        file: File,
        outputBytes: ByteArray,
        bufferDays: Int,
        actedOn: RenewalReason?,
    ): Either<String, RenewalAssessment> {
        val directory = file.absoluteFile.toPath().parent
            ?: return "could not resolve a directory to validate the renewed document".left()
        val verifyFile = try {
            createTempFile(directory, ".${file.name}.verify.", ".tmp")
        } catch (e: Exception) {
            return "could not stage the renewed document for validation: ${e.message}".left()
        }
        return try {
            verifyFile.writeBytes(outputBytes)
            checkRenewalUseCase(verifyFile.absolutePathString(), bufferDays).fold(
                ifLeft = { "the renewed document failed validation: ${it.message}".left() },
                ifRight = { assessment ->
                    if (assessment.need == RenewalNeed.NEEDED && assessment.reason == actedOn) {
                        "the renewed document still reports the same need (${actedOn?.name})".left()
                    } else {
                        assessment.right()
                    }
                },
            )
        } catch (e: Exception) {
            "could not validate the renewed document: ${e.message}".left()
        } finally {
            verifyFile.deleteIfExists()
        }
    }

    /**
     * Seal a document that has just been refreshed, in the same run.
     *
     * Refreshing stale revocation data leaves the document at B-LT with material that now covers the
     * signature but has nothing anchoring it — the state the next run would seal anyway. Doing it
     * here spares the document a day in the one state that carries no proof of existence at all,
     * without weakening the rule that produced the two steps: the seal happens only after the
     * refreshed bytes have been re-assessed and found to need exactly sealing, so stale material can
     * still never be frozen under a timestamp.
     *
     * A failure is not an error. The refresh itself succeeded and is worth keeping; the caller writes
     * the refreshed document and the next run seals it, which is precisely the behaviour this
     * optimisation shortcuts.
     *
     * @return the sealed bytes, or `null` when sealing did not happen and the refreshed bytes stand.
     */
    private suspend fun sealAfterRefresh(
        file: File,
        refreshedBytes: ByteArray,
        resolvedConfig: ResolvedConfig,
        bufferDays: Int,
    ): ByteArray? {
        val sealed = extendDocumentUseCase(
            ArchivingParameters(
                inputBytes = refreshedBytes,
                inputName = file.name,
                targetLevel = SignatureLevel.PADES_BASELINE_LTA,
                resolvedConfig = resolvedConfig,
            )
        ).getOrNull() ?: return null

        if (sealed.achievedLevel != SignatureLevel.PADES_BASELINE_LTA) return null
        return verifyRenewedOutput(file, sealed.outputBytes, bufferDays, RenewalReason.LT_NOT_SEALED)
            .fold(ifLeft = { null }, ifRight = { sealed.outputBytes })
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
         * How many consecutive documents may fail against the same TSA before a run stops calling it.
         *
         * A threshold above one keeps the breaker from making things worse. Without a breaker, a brief
         * outage costs only the documents attempted while it lasted; tripping on the first failure
         * would turn that same outage into a lost batch.
         *
         * The attempts are spent on successive documents rather than on retrying one, so that a server
         * which is merely flaky still makes progress: three separate documents may partly succeed
         * where three retries of the same one would not.
         */
        internal const val TSA_FAILURE_LIMIT: Int = 3

        /**
         * How many runs in a row must be killed before the user is told about it.
         *
         * One interruption is an ordinary restart landing on the nightly batch, and saying so would
         * be noise. Three in a row is a pattern, and reaching it takes about as many days — far
         * sooner than the staleness alert's default fortnight, which in any case never fires for
         * interrupted runs because it is only evaluated by a run that finishes.
         */
        internal const val INTERRUPTION_ALERT_THRESHOLD: Int = 3

        /**
         * Formats the UTC instant embedded in a backup file name, in basic ISO-8601
         * (e.g. `20260614T020000Z`) so the name is a valid filename on Windows (no `:`).
         */
        private val BACKUP_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}






