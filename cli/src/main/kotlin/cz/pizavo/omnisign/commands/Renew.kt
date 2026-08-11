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
import cz.pizavo.omnisign.data.service.RenewalNotifier
import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import cz.pizavo.omnisign.domain.model.result.RenewFileStatus
import cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI command that executes all configured renewal jobs
 * (or a single named job), checks each matched PDF against its renewal buffer, and
 * re-timestamps it in-place — to PAdES B-LTA — when its archival timestamp (or a signature
 * timestamp not yet sealed by one) is nearing expiry. Because the target is always B-LTA,
 * a matched B-T or B-LT document is promoted to B-LTA as part of renewal.
 *
 * This command is designed to be invoked by the OS-level daily scheduled job registered via
 * `omnisign schedule install`, but can also be run manually at any time.
 *
 * The core batch logic is delegated to [RenewBatchUseCase]; this command handles
 * CLI-specific concerns: console output, JSON formatting, and OS notifications.
 */
class Renew : CliktCommand(name = "renew"), KoinComponent {

	private val renewBatchUseCase: RenewBatchUseCase by inject()
	private val renewalNotifier: RenewalNotifier by inject()
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
		"Run configured renewal jobs: re-timestamp matched PDFs with expiring timestamps to B-LTA in place (also promotes matched B-T/B-LT documents to B-LTA)"

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

		if (result.alreadyRunning) {
			if (output.json) {
				echo(Json.encodeToString(JsonRenewalResult(success = true, alreadyRunning = true)))
			} else {
				echo("⏳ Another renewal run is already in progress — skipping.")
			}
			renewalNotifier.notify(result)
			return@runBlocking
		}

		if (result.lockError != null) {
			if (output.json) {
				echo(
					Json.encodeToString(
						JsonRenewalResult(
							success = false,
							error = JsonError(message = "Could not acquire the renewal lock: ${result.lockError}"),
						)
					)
				)
			} else {
				echo("❌ Could not acquire the renewal lock: ${result.lockError}", err = true)
			}
			renewalNotifier.notify(result)
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
		renewalNotifier.notify(result)

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
						unrecoverable = result.unrecoverable,
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
										reason = f.reason?.name,
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
						RenewFileStatus.Status.SKIPPED,
						RenewFileStatus.Status.SKIPPED_BY_POLICY -> "✔"
						RenewFileStatus.Status.DRY_RUN -> "🔶"
						RenewFileStatus.Status.UNRECOVERABLE -> "⛔"
						RenewFileStatus.Status.ERROR,
						RenewFileStatus.Status.CONFIG_ERROR -> "❌"
					}
					val label = when (f.status) {
						RenewFileStatus.Status.RENEWED -> "[RENEWED] ${f.path}"
						RenewFileStatus.Status.SKIPPED -> "[SKIP]  ${f.path} — ${f.message ?: "nothing due yet"}"
						RenewFileStatus.Status.SKIPPED_BY_POLICY -> "[SKIP]  ${f.path} — ${f.message}"
						RenewFileStatus.Status.DRY_RUN -> "[DRY-RUN] ${f.path} — would be extended"
						RenewFileStatus.Status.UNRECOVERABLE -> "[TERMINAL] ${f.path} — ${f.message}"
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
			if (result.unrecoverable > 0) {
				echo("  Terminal: ${result.unrecoverable} (deadline passed — no further attempt can succeed)")
			}
			echo("═══════════════════════════════════════════════════════════════")
		}
	}
}
