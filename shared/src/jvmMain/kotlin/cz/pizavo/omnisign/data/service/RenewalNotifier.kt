package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewBatchResult

/**
 * Turns a completed [RenewBatchResult] into one OS notification per job that requested them, giving
 * the CLI `renew` command and the desktop headless renewal a single, consistently-branded source
 * for the partial-failure / failure / completion notification ladder.
 *
 * Nothing is sent for a dry-run, for a job that opted out of notifications, or for a job where
 * nothing actionable happened (every file was skipped).
 *
 * @property notificationService The platform OS-notification sink.
 */
class RenewalNotifier(private val notificationService: OsNotificationService) {

	/**
	 * Fire a summary OS notification for each notifying job in [result].
	 *
	 * A dry-run notifies nothing, since no files were changed. For each job that requested
	 * notifications, a single notification is sent: a [NotificationUrgency.CRITICAL] partial-failure
	 * note when some files renewed but others errored, a [NotificationUrgency.CRITICAL] failure note
	 * when only errors occurred, or a [NotificationUrgency.NORMAL] completion note when files renewed
	 * cleanly. A job in which every file was skipped produces no notification.
	 *
	 * @param result The aggregated outcome of a renewal batch run.
	 */
	fun notify(result: RenewBatchResult) {
		if (result.dryRun) return
		for (job in result.jobs) {
			if (!job.notify) continue
			when {
				job.errors > 0 && job.renewed > 0 -> notificationService.notify(
					title = "$PRODUCT — Renewal partial failure (${job.name})",
					body = "${job.renewed} file(s) re-timestamped, ${job.errors} error(s). " +
							"Check the log for details.",
					urgency = NotificationUrgency.CRITICAL,
				)

				job.errors > 0 -> notificationService.notify(
					title = "$PRODUCT — Renewal failed (${job.name})",
					body = "${job.errors} file(s) could not be re-timestamped. " +
							"Digital continuity may be at risk. Check the log.",
					urgency = NotificationUrgency.CRITICAL,
				)

				job.renewed > 0 -> notificationService.notify(
					title = "$PRODUCT — Renewal complete (${job.name})",
					body = "${job.renewed} file(s) successfully re-timestamped.",
					urgency = NotificationUrgency.NORMAL,
				)
			}
		}
	}

	companion object {
		/**
		 * The product name shown in user-facing notification titles.
		 */
		private const val PRODUCT = "OmniSign"
	}
}
