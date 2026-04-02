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
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Clock

/**
 * Executes all configured renewal jobs (or a single named job), checking each
 * matching B-LTA PDF against its renewal buffer and re-timestamping in place
 * any file whose archival timestamp is nearing expiry.
 *
 * This use case encapsulates the core batch logic shared by the CLI `renew`
 * command and the desktop app's headless renewal mode. Presentation concerns
 * (console output, JSON formatting) remain in the caller.
 *
 * @param checkRenewalUseCase Checks whether a single document needs renewal.
 * @param extendDocumentUseCase Extends a document to the target PAdES level.
 * @param configRepository Provides the current [AppConfig] with renewal jobs.
 */
class RenewBatchUseCase(
    private val checkRenewalUseCase: CheckArchivalRenewalUseCase,
    private val extendDocumentUseCase: ExtendDocumentUseCase,
    private val configRepository: ConfigRepository,
) {

    /**
     * Run renewal jobs and return an aggregated [RenewBatchResult].
     *
     * @param jobName Optional name of a single job to execute. When `null`, all
     *   configured jobs are processed.
     * @param dryRun When `true`, files that need renewal are reported but not
     *   modified.
     * @return A [RenewBatchResult] summarising every job and file outcome, or
     *   `null` when the requested [jobName] does not exist.
     */
    suspend operator fun invoke(
        jobName: String? = null,
        dryRun: Boolean = false,
    ): RenewBatchResult? {
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
            val files = resolveGlobs(job.globs)
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

                        extendDocumentUseCase(
                            ArchivingParameters(
                                inputFile = path,
                                outputFile = path,
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
     */
    internal fun resolveGlobs(globs: List<String>): List<File> {
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

            Files.walk(rootPath)
                .filter { path ->
                    Files.isRegularFile(path) && matcher.matches(rootPath.relativize(path))
                }
                .sorted()
                .forEach { path ->
                    val abs = path.toAbsolutePath().normalize().toString()
                    if (seen.add(abs)) results.add(path.toFile())
                }
        }
        return results
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
     * ISO-8601 timestamp. Silently ignores write failures.
     */
    private fun appendLog(logFile: String?, message: String) {
        if (logFile == null) return
        try {
            File(logFile).apply { parentFile?.mkdirs() }
                .appendText("${Clock.System.now()} $message\n")
        } catch (_: Exception) {
        }
    }
}






