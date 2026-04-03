package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.cli.OutputConfig
import cz.pizavo.omnisign.cli.json.JsonError
import cz.pizavo.omnisign.cli.json.JsonRenewalFileResult
import cz.pizavo.omnisign.cli.json.JsonRenewalJobResult
import cz.pizavo.omnisign.cli.json.JsonRenewalResult
import cz.pizavo.omnisign.data.service.NotificationUrgency
import cz.pizavo.omnisign.data.service.OsNotificationService
import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI command that executes all configured renewal jobs
 * (or a single named job), checks each matching B-LTA PDF against its renewal buffer, and
 * re-timestamps in-place any file whose archival timestamp is nearing expiry.
 *
 * This command is designed to be invoked by the OS-level daily cron job registered via
 * `omnisign schedule install`, but can also be run manually at any time.
 *
 * The core batch logic is delegated to [RenewBatchUseCase]; this command handles
 * CLI-specific concerns: console output, JSON formatting, and OS notifications.
 */
class Renew : CliktCommand(name = "renew"), KoinComponent {

	private val renewBatchUseCase: RenewBatchUseCase by inject()
	private val notificationService: OsNotificationService by inject()
	private val output by requireObject<OutputConfig>()

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
		val result = renewBatchUseCase(jobName = jobName, dryRun = dryRun)

		if (result == null) {
			if (output.json) {
				echo(
					Json.encodeToString(
						JsonRenewalResult(
							success = false,
							error = JsonError(message = "Renewal job '$jobName' not found.")
						)
					)
				)
			} else {
				echo("❌ Renewal job '$jobName' not found.", err = true)
			}
			throw ProgramResult(1)
		}

		if (result.jobs.isEmpty()) {
			if (output.json) {
				echo(Json.encodeToString(JsonRenewalResult(success = true, dryRun = dryRun)))
			} else {
				echo("No renewal jobs configured. Use `omnisign schedule job add` to add one.")
			}
			return@runBlocking
		}

		printResults(result)
		sendNotifications(result)

		if (result.errors > 0) throw ProgramResult(1)
	}

	/**
	 * Print per-job and per-file results to the console in human-readable or JSON format.
	 */
	private fun printResults(result: RenewBatchResult) {
		if (output.json) {
			echo(
				Json.encodeToString(
					JsonRenewalResult(
						success = result.success,
						checked = result.checked,
						renewed = result.renewed,
						skipped = result.skipped,
						errors = result.errors,
						dryRun = result.dryRun,
						jobs = result.jobs.map { job ->
							JsonRenewalJobResult(
								name = job.name,
								files = job.files.map { f ->
									JsonRenewalFileResult(
										path = f.path,
										status = f.status.name,
										message = f.message,
										warnings = f.warnings,
									)
								},
							)
						},
					)
				)
			)
		} else {
			for (job in result.jobs) {
				echo("\n▶ Job: ${job.name}")
				if (job.files.isEmpty()) {
					echo("  No files matched globs.")
					continue
				}
				for (f in job.files) {
					val icon = when (f.status) {
						RenewFileStatus.Status.RENEWED -> "✅"
						RenewFileStatus.Status.SKIPPED -> "✔"
						RenewFileStatus.Status.DRY_RUN -> "🔶"
						RenewFileStatus.Status.ERROR,
						RenewFileStatus.Status.CONFIG_ERROR -> "❌"
					}
					val label = when (f.status) {
						RenewFileStatus.Status.RENEWED -> "[RENEWED] ${f.path}"
						RenewFileStatus.Status.SKIPPED -> "[SKIP]  ${f.path} — timestamp still valid"
						RenewFileStatus.Status.DRY_RUN -> "[DRY-RUN] ${f.path} — would be re-timestamped"
						RenewFileStatus.Status.ERROR -> "[ERROR] ${f.path} — ${f.message}"
						RenewFileStatus.Status.CONFIG_ERROR -> "[ERROR] Configuration Error: ${f.message}"
					}
					val isError = f.status == RenewFileStatus.Status.ERROR ||
							f.status == RenewFileStatus.Status.CONFIG_ERROR
					echo("  $icon $label", err = isError)
					if (f.status == RenewFileStatus.Status.RENEWED) {
						f.warnings.forEach { w -> echo("  ⚠️ ${f.path} — $w", err = true) }
					}
				}
			}

			echo("")
			echo("═══════════════════════════════════════════════════════════════")
			echo("                      RENEWAL SUMMARY")
			echo("═══════════════════════════════════════════════════════════════")
			echo("  Checked : ${result.checked}")
			echo("  Renewed : ${result.renewed}${if (result.dryRun) " (dry-run)" else ""}")
			echo("  Skipped : ${result.skipped}")
			echo("  Errors  : ${result.errors}")
			echo("═══════════════════════════════════════════════════════════════")
		}
	}

	/**
	 * Fire OS notifications for each job that requested them.
	 */
	private fun sendNotifications(result: RenewBatchResult) {
		if (result.dryRun) return
		for (job in result.jobs) {
			if (!job.notify) continue
			notifyJobResult(job.name, job.renewed, job.errors)
		}
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
}
