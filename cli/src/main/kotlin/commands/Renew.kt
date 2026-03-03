package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.data.service.NotificationUrgency
import cz.pizavo.omnisign.data.service.OsNotificationService
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.CheckArchivalRenewalUseCase
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

/**
 * CLI command that executes all configured [cz.pizavo.omnisign.domain.model.config.RenewalJob]s
 * (or a single named job), checks each matching B-LTA PDF against its renewal buffer, and
 * re-timestamps in-place any file whose archival timestamp is nearing expiry.
 *
 * This command is designed to be invoked by the OS-level daily cron job registered via
 * `omnisign schedule install`, but can also be run manually at any time.
 */
class Renew : CliktCommand(name = "renew"), KoinComponent {
	
	private val checkRenewalUseCase: CheckArchivalRenewalUseCase by inject()
	private val extendUseCase: ExtendDocumentUseCase by inject()
	private val configRepository: ConfigRepository by inject()
	private val notificationService: OsNotificationService by inject()
	
	private val jobName by option(
		"-j", "--job",
		help = "Run only the named renewal job. Runs all jobs when omitted."
	)
	
	private val dryRun by option(
		"--dry-run",
		help = "Check which files need renewal and report them, but do not modify any file."
	).flag(default = false)
	
	override fun help(context: Context): String =
		"Run configured renewal jobs: check B-LTA PDFs for expiring timestamps and re-timestamp them in place"
	
	override fun run(): Unit = runBlocking {
		val appConfig = configRepository.getCurrentConfig()
		
		val jobsToRun = if (jobName != null) {
			val job = appConfig.renewalJobs[jobName]
			if (job == null) {
				echo("❌ Renewal job '$jobName' not found.", err = true)
				return@runBlocking
			}
			mapOf(jobName!! to job)
		} else {
			appConfig.renewalJobs
		}
		
		if (jobsToRun.isEmpty()) {
			echo("No renewal jobs configured. Use `omnisign schedule job add` to add one.")
			return@runBlocking
		}
		
		var totalChecked = 0
		var totalRenewed = 0
		var totalSkipped = 0
		var totalErrors = 0
		
		for ((_, job) in jobsToRun) {
			echo("\n▶ Job: ${job.name}")
			val files = resolveGlobs(job.globs)
			
			if (files.isEmpty()) {
				echo("  No files matched globs: ${job.globs.joinToString()}")
				continue
			}
			
			val resolvedConfigResult = resolveJobConfig(appConfig, job)
			if (resolvedConfigResult.isLeft()) {
				val error = resolvedConfigResult.leftOrNull()!!
				echo("  ❌ Configuration Error for job '${job.name}': ${error.message}", err = true)
				totalErrors++
				continue
			}
			val resolvedConfig = resolvedConfigResult.getOrNull()!!
			
			var jobRenewed = 0
			var jobErrors = 0
			
			for (file in files) {
				totalChecked++
				val path = file.absolutePath
				
				checkRenewalUseCase(path, job.renewalBufferDays).fold(
					ifLeft = { error ->
						totalErrors++
						jobErrors++
						val msg = "[ERROR] $path — ${error.message}"
						echo("  ❌ $msg", err = true)
						job.logFile?.let { appendLog(it, msg) }
					},
					ifRight = { needsRenewal ->
						if (!needsRenewal) {
							totalSkipped++
							val msg = "[SKIP]  $path — timestamp still valid"
							echo("  ✔ $msg")
							job.logFile?.let { appendLog(it, msg) }
							return@fold
						}
						
						if (dryRun) {
							totalRenewed++
							jobRenewed++
							val msg = "[DRY-RUN] $path — would be re-timestamped"
							echo("  🔶 $msg")
							job.logFile?.let { appendLog(it, msg) }
							return@fold
						}
						
						extendUseCase(
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
								val msg = "[ERROR] $path — renewal failed: ${error.message}"
								echo("  ❌ $msg", err = true)
								job.logFile?.let { appendLog(it, msg) }
							},
							ifRight = {
								totalRenewed++
								jobRenewed++
								val msg = "[RENEWED] $path"
								echo("  ✅ $msg")
								job.logFile?.let { appendLog(it, msg) }
							}
						)
					}
				)
			}
			
			if (job.notify && !dryRun) {
				notifyJobResult(job.name, jobRenewed, jobErrors)
			}
		}
		
		echo("")
		echo("═══════════════════════════════════════════════════════════════")
		echo("                      RENEWAL SUMMARY")
		echo("═══════════════════════════════════════════════════════════════")
		echo("  Checked : $totalChecked")
		echo("  Renewed : $totalRenewed${if (dryRun) " (dry-run)" else ""}")
		echo("  Skipped : $totalSkipped")
		echo("  Errors  : $totalErrors")
		echo("═══════════════════════════════════════════════════════════════")
	}
	
	/**
	 * Expand a list of glob patterns to a deduplicated, sorted list of existing PDF files.
	 */
	private fun resolveGlobs(globs: List<String>): List<File> {
		val seen = LinkedHashSet<String>()
		val results = mutableListOf<File>()
		for (glob in globs) {
			val (root, pattern) = splitGlob(glob)
			val rootPath = Paths.get(root).toAbsolutePath()
			val absolutePattern = rootPath.fileSystem.let { fs ->
				fs.getPathMatcher("glob:$pattern")
			}
			Files.walk(rootPath)
				.filter { Files.isRegularFile(it) && absolutePattern.matches(it.toAbsolutePath()) }
				.sorted()
				.forEach { path ->
					val abs = path.toAbsolutePath().toString()
					if (seen.add(abs)) results.add(path.toFile())
				}
		}
		return results
	}
	
	/**
	 * Split a glob string into an absolute root directory and a full glob pattern.
	 * Patterns without wildcards are treated as literal file paths.
	 * The returned pattern is converted to an absolute path so that [java.nio.file.PathMatcher]
	 * can match against the absolute paths yielded by [Files.walk].
	 */
	private fun splitGlob(glob: String): Pair<String, String> {
		val normalised = glob.replace('\\', '/')
		val wildcardIndex = normalised.indexOfFirst { it == '*' || it == '?' || it == '{' || it == '[' }
		val rootPath = if (wildcardIndex == -1) {
			File(glob).parent ?: "."
		} else {
			val prefix = normalised.substring(0, wildcardIndex)
			val lastSlash = prefix.lastIndexOf('/')
			if (lastSlash == -1) "." else normalised.substring(0, lastSlash)
		}
		val absoluteGlob = Paths.get(glob).toAbsolutePath().toString()
		return rootPath to absoluteGlob
	}
	
	/**
	 * Build a [ResolvedConfig] for a renewal job, honoring the job's optional profile override.
	 * Returns [Either.Left] with a [cz.pizavo.omnisign.domain.model.error.ConfigurationError.InvalidConfiguration]
	 * when any resolved algorithm is in the disabled set.
	 */
	private fun resolveJobConfig(
		appConfig: cz.pizavo.omnisign.domain.model.config.AppConfig,
		job: RenewalJob,
	): Either<cz.pizavo.omnisign.domain.model.error.ConfigurationError.InvalidConfiguration, ResolvedConfig> {
		val profileName = job.profile ?: appConfig.activeProfile
		val profileConfig = profileName?.let { appConfig.profiles[it] }
		return ResolvedConfig.resolve(
			global = appConfig.global,
			profile = profileConfig,
			operationOverrides = null,
		)
	}
	
	/**
	 * Fire a single summary OS notification for one completed job run.
	 * No notification is sent when everything was skipped (nothing actionable happened).
	 */
	private fun notifyJobResult(jobName: String, renewed: Int, errors: Int) {
		when {
			errors > 0 && renewed > 0 -> notificationService.notify(
				title = "omnisign — Renewal partial failure ($jobName)",
				body = "$renewed file(s) re-timestamped, $errors error(s). Check the log for details.",
				urgency = NotificationUrgency.CRITICAL,
			)
			
			errors > 0 -> notificationService.notify(
				title = "omnisign — Renewal failed ($jobName)",
				body = "$errors file(s) could not be re-timestamped. Digital continuity may be at risk. Check the log.",
				urgency = NotificationUrgency.CRITICAL,
			)
			
			renewed > 0 -> notificationService.notify(
				title = "omnisign — Renewal complete ($jobName)",
				body = "$renewed file(s) successfully re-timestamped.",
				urgency = NotificationUrgency.NORMAL,
			)
		}
	}
	
	/**
	 * Append a single structured log line to [logFile], prefixed with an ISO-8601 timestamp.
	 */
	private fun appendLog(logFile: String, message: String) {
		try {
			File(logFile).apply { parentFile?.mkdirs() }
				.appendText("${Instant.now()} $message\n")
		} catch (_: Exception) {
			// Log failure must never abort the renewal run.
		}
	}
}





