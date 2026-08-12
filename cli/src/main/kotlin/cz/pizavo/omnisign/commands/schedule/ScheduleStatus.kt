package cz.pizavo.omnisign.commands.schedule

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.data.preferences.loadFormatPreferences
import cz.pizavo.omnisign.data.service.OsSchedulerService
import cz.pizavo.omnisign.domain.model.result.label
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.domain.port.RenewalActivityProbe
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Reports whether the daily `omnisign renew` OS job is registered, and summarises the most recent
 * renewal run (when it ran, whether it succeeded, and any failures since the last success).
 *
 * A run that began but never reported a result is called out rather than left looking like the last
 * successful one. That is what a system restart mid-batch produces, since no run record, notification
 * or job-log entry is written in that case. [RenewalActivityProbe] separates it from a run that is
 * simply still going.
 */
class ScheduleStatus : CliktCommand(name = "status"), KoinComponent {
	private val scheduler: OsSchedulerService by inject()
	private val runRecordStore: RenewalRunRecordStore by inject()
	private val activityProbe: RenewalActivityProbe by inject()

	override fun help(context: Context): String =
		"Show whether the daily automatic re-timestamping job is registered"

	override fun run() {
		if (scheduler.isInstalled()) {
			echo("✅ Daily renewal job is installed.")
		} else {
			echo("⚪ Daily renewal job is NOT installed. Run `omnisign schedule install` to set it up.")
		}
		printLastRun()
	}

	/**
	 * Print a summary of the most recent renewal run, or a note when none has been recorded yet.
	 */
	private fun printLastRun() {
		val record = runRecordStore.load()
		if (record == null) {
			echo("No renewal run has been recorded yet.")
			return
		}
		val dateFormat = loadFormatPreferences().dateFormat
		val indent = " ".repeat(3)
		echo("")
		echo("Last successful run: ${record.lastSuccessAt?.formatDateTime(dateFormat = dateFormat) ?: "never"}")
		echo(
			"Last run: ${record.lastRunAt.formatDateTime(dateFormat = dateFormat)} — ${record.outcome.label} " +
					"(checked ${record.checked}, renewed ${record.renewed}, " +
					"skipped ${record.skipped}, errors ${record.errors})"
		)
		record.runStartedAt?.let { startedAt ->
			val started = startedAt.formatDateTime(dateFormat = dateFormat)
			if (activityProbe.isRunInFlight()) {
				echo("A renewal run has been in progress since $started.")
			} else {
				echo("⚠ The renewal run started $started never finished — it was interrupted.")
			}
		}
		if (record.warnings.isNotEmpty()) {
			echo("${record.warnings.size} warning(s):")
			record.warnings.forEach { echo("$indent- $it") }
		}
		if (record.failuresSinceSuccess > 0) {
			echo("⚠ ${record.failuresSinceSuccess} unsuccessful run(s) since the last success.")
			record.failureReason?.let { echo("${indent}reason: $it") }
			record.errorDetails.forEach { echo("$indent- ${it.path}: ${it.message}") }
		}
	}
}
