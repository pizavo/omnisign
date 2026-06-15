package cz.pizavo.omnisign.domain.usecase

import arrow.core.Either
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.model.result.RenewJobResult
import cz.pizavo.omnisign.domain.model.result.RenewalRunError
import cz.pizavo.omnisign.domain.model.result.RenewalRunJobSummary
import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
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
import kotlin.time.toJavaInstant

private val logger = KotlinLogging.logger {}

/**
 * Executes all configured renewal jobs (or a single named job), checking each
 * matching B-LTA PDF against its renewal buffer and re-timestamping in place
 * any file whose outermost document timestamp — or a signature timestamp not yet
 * sealed by one — is nearing the expiry of its signing certificate.
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
     * the lock, this call does nothing and returns a result with [RenewBatchResult.alreadyRunning]
     * set, so two schedulers — or a manual run overlapping the scheduled one — can never
     * re-timestamp the same documents concurrently. If the lock cannot be established at all, the
     * run is **not** attempted and a result with [RenewBatchResult.lockError] is returned.
     *
     * @param jobName Optional name of a single job to execute. When `null`, all
     *   configured jobs are processed.
     * @param dryRun When `true`, files that need renewal are reported but not
     *   modified.
     * @return A [RenewBatchResult] summarising every job and file outcome; a result with
     *   [RenewBatchResult.alreadyRunning] when another run holds the lock; a result with
     *   [RenewBatchResult.lockError] when the lock could not be acquired; or `null` when the
     *   requested [jobName] does not exist.
     */
    suspend operator fun invoke(
        jobName: String? = null,
        dryRun: Boolean = false,
    ): RenewBatchResult? {
        val lock = try {
            renewalLock.tryAcquire()
        } catch (e: Exception) {
            val result = RenewBatchResult(lockError = e.message ?: "the renewal lock could not be acquired")
            recordRun(result)
            return result
        }
        if (lock == null) return RenewBatchResult(alreadyRunning = true)
        return try {
            runBatch(jobName, dryRun).also { result ->
                if (result != null && !dryRun) recordRun(result)
            }
        } finally {
            lock.close()
        }
    }

    /**
     * Persist a [RenewalRunRecord] summarising [result], carrying the last-success timestamp
     * forward and counting consecutive failures since it. Never called for dry-runs or for runs
     * skipped because another run held the lock. A persistence failure is logged and otherwise
     * ignored, so status bookkeeping can never break a run.
     */
    private fun recordRun(result: RenewBatchResult) {
        try {
            val now = Clock.System.now()
            val previous = runRecordStore.load()
            val outcome = when {
                result.lockError != null -> RenewalRunOutcome.FAILED
                result.errors > 0 -> RenewalRunOutcome.COMPLETED_WITH_ERRORS
                else -> RenewalRunOutcome.SUCCESS
            }
            val succeeded = outcome == RenewalRunOutcome.SUCCESS
            val errorDetails = result.jobs.flatMap { job ->
                job.files
                    .filter { it.status == RenewFileStatus.Status.ERROR || it.status == RenewFileStatus.Status.CONFIG_ERROR }
                    .map { RenewalRunError(path = it.path, message = it.message ?: "unknown error") }
            }
            val warnings = result.jobs.flatMap { it.files }.flatMap { it.warnings }.distinct()
            val jobs = result.jobs.map { RenewalRunJobSummary(name = it.name, renewed = it.renewed, errors = it.errors) }
            runRecordStore.save(
                RenewalRunRecord(
                    lastRunAt = now,
                    outcome = outcome,
                    checked = result.checked,
                    renewed = result.renewed,
                    skipped = result.skipped,
                    errors = result.errors,
                    failureReason = result.lockError,
                    errorDetails = errorDetails,
                    warnings = warnings,
                    jobs = jobs,
                    lastSuccessAt = if (succeeded) now else previous?.lastSuccessAt,
                    failuresSinceSuccess = if (succeeded) 0 else (previous?.failuresSinceSuccess ?: 0) + 1,
                )
            )
        } catch (e: Exception) {
            logger.warn(e) { "Could not persist the renewal run record" }
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
                    )
                )
                continue
            }
            val resolvedConfig = resolvedConfigResult.getOrNull()!!

            var jobRenewed = 0
            var jobErrors = 0
            val fileStatuses = mutableListOf<RenewFileStatus>()

            for (file in files) {
                totalChecked++
                val path = file.absolutePath

                checkRenewalUseCase(path, job.renewalBufferDays).fold(
                    ifLeft = { error ->
                        totalErrors++
                        jobErrors++
                        appendLog(job.logFile, "[ERROR] $path — ${error.message}")
                        fileStatuses.add(
                            RenewFileStatus(path = path, status = RenewFileStatus.Status.ERROR, message = error.message)
                        )
                    },
                    ifRight = { needsRenewal ->
                        if (!needsRenewal) {
                            totalSkipped++
                            appendLog(job.logFile, "[SKIP]  $path — timestamp still valid")
                            fileStatuses.add(RenewFileStatus(path = path, status = RenewFileStatus.Status.SKIPPED))
                            return@fold
                        }

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
                            appendLog(job.logFile, "[ERROR] $path — $writabilityError")
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
                            appendLog(job.logFile, "[ERROR] $path — renewal failed: could not read file")
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
                                targetLevel = SignatureLevel.PADES_BASELINE_LTA,
                                resolvedConfig = resolvedConfig,
                            )
                        ).fold(
                            ifLeft = { error ->
                                totalErrors++
                                jobErrors++
                                appendLog(job.logFile, "[ERROR] $path — renewal failed: ${error.message}")
                                fileStatuses.add(
                                    RenewFileStatus(
                                        path = path,
                                        status = RenewFileStatus.Status.ERROR,
                                        message = error.message,
                                    )
                                )
                            },
                            ifRight = { result ->
                                val validationError = verifyRenewedOutput(file, result.outputBytes, job.renewalBufferDays)
                                if (validationError != null) {
                                    totalErrors++
                                    jobErrors++
                                    appendLog(job.logFile, "[ERROR] $path — $validationError")
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
                                        appendLog(job.logFile, "[ERROR] $path — backup failed: ${backupError.message}")
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
                                    appendLog(job.logFile, "[ERROR] $path — renewal failed: ${writeError.message}")
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
                                appendLog(job.logFile, "[RENEWED] $path")
                                result.rawWarnings.forEach { w ->
                                    appendLog(job.logFile, "[WARN] $path — $w")
                                }
                                fileStatuses.add(
                                    RenewFileStatus(
                                        path = path,
                                        status = RenewFileStatus.Status.RENEWED,
                                        warnings = result.warnings,
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
                    notify = job.notify,
                )
            )
        }

        return RenewBatchResult(
            checked = totalChecked,
            renewed = totalRenewed,
            skipped = totalSkipped,
            errors = totalErrors,
            dryRun = dryRun,
            jobs = jobResults,
        )
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
            try {
                Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile && matcher.matches(rootPath.relativize(file))) {
                            matched.add(file)
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
     * overwrite [file]. A sound renewal must parse and report that it *no longer* needs renewal;
     * otherwise the extension produced a malformed or no-stronger document and the original must be
     * preserved rather than replaced.
     *
     * The bytes are checked through a short-lived verify file in [file]'s own directory — the same
     * path that decides renewal in the first place — which is always deleted afterwards.
     *
     * @return `null` when the renewed document is sound, or a human-readable reason it is not.
     */
    private suspend fun verifyRenewedOutput(file: File, outputBytes: ByteArray, bufferDays: Int): String? {
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
                ifRight = { stillNeedsRenewal ->
                    if (stillNeedsRenewal) "the renewed document still reports that it needs renewal" else null
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

    companion object {
        /**
         * Formats the UTC instant embedded in a backup file name, in basic ISO-8601
         * (e.g. `20260614T020000Z`) so the name is a valid filename on Windows (no `:`).
         */
        private val BACKUP_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}






