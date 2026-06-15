package cz.pizavo.omnisign.commands.schedule

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.data.service.OsSchedulerService
import cz.pizavo.omnisign.domain.model.result.label
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Reports whether the daily `omnisign renew` OS job is registered, and summarises the most recent
 * renewal run (when it ran, whether it succeeded, and any failures since the last success).
 */
class ScheduleStatus : CliktCommand(name = "status"), KoinComponent {
	private val scheduler: OsSchedulerService by inject()
	private val runRecordStore: RenewalRunRecordStore by inject()

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
		val indent = " ".repeat(3)
		echo("")
		echo("Last successful run: ${record.lastSuccessAt?.formatDateTime() ?: "never"}")
		echo(
			"Last run: ${record.lastRunAt.formatDateTime()} — ${record.outcome.label} " +
					"(checked ${record.checked}, renewed ${record.renewed}, " +
					"skipped ${record.skipped}, errors ${record.errors})"
		)
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
