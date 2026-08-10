package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * Turns a [RenewBatchResult] into OS notifications: a single run-wide notification when the renewal
 * lock could not be acquired (so the run never started), otherwise one notification per job that
 * requested them — giving the CLI `renew` command and the desktop headless renewal a single,
 * consistently-branded source for the lock-failure / partial-failure / failure / completion ladder.
 *
 * Nothing is sent for a dry-run, for a job that opted out of notifications, or for a job where
 * nothing actionable happened (every file was skipped). The lock-failure notification is the one
 * exception to the per-job opt-in: since no job ran, it always fires — a renewal subsystem that
 * cannot even start is a run-wide failure rather than a per-job outcome. The staleness notification
 * (carried in [RenewBatchResult.stalenessAlert]) is likewise run-wide: it fires on top of any
 * per-job outcome when renewal has gone too long without a success, gated by its own setting rather
 * than the per-job opt-in.
 *
 * A job that found documents past their preservation deadline raises a second notification on top of
 * its outcome one, in the same way the staleness alert does — the two say different things and one
 * must not hide the other. It counts only the *newly* terminal documents
 * ([RenewJobResult.newlyUnrecoverable]): the condition cannot be acted on and cannot change, so
 * repeating it every night would train the user to ignore it.
 *
 * Titles and bodies are resolved from the `renewal-notifications` resource bundle in the locale
 * supplied by [localeProvider], so a Czech-configured run shows Czech text while any other locale
 * falls back to the English base bundle.
 *
 * @property notificationService The platform OS-notification sink.
 * @property localeProvider Supplies the locale used to resolve the message bundle. Evaluated on each
 *   [notify] call so a late [Locale.setDefault] — e.g. from the persisted UI language in the headless
 *   renewal entry point — is honored. Defaults to the JVM default locale.
 */
class RenewalNotifier(
	private val notificationService: OsNotificationService,
	private val localeProvider: () -> Locale = { Locale.getDefault() },
) {

	/**
	 * Fire OS notifications summarising [result].
	 *
	 * A dry-run notifies nothing, since no files were changed. When the run never started because the
	 * renewal lock could not be acquired ([RenewBatchResult.lockError]), a single
	 * [NotificationUrgency.CRITICAL] lock-failure notification is sent and no per-job notifications
	 * follow — this fires regardless of any job's opt-in, since nothing ran. Otherwise, for each job
	 * that requested notifications, a single notification is sent: a [NotificationUrgency.CRITICAL]
	 * partial-failure note when some files renewed but others errored, a [NotificationUrgency.CRITICAL]
	 * failure note when only errors occurred, or a [NotificationUrgency.NORMAL] completion note when
	 * files renewed cleanly. A job in which every file was skipped produces no notification. Finally,
	 * when [result] carries a [RenewBatchResult.stalenessAlert], an additional
	 * [NotificationUrgency.CRITICAL] staleness notification is sent — independently of the per-job
	 * notifications above — warning that renewal has stalled for too long.
	 *
	 * @param result The aggregated outcome of a renewal batch run.
	 */
	fun notify(result: RenewBatchResult) {
		if (result.dryRun) return
		val messages = ResourceBundle.getBundle(BUNDLE, localeProvider(), NO_FALLBACK)
		if (result.lockError != null) {
			notificationService.notify(
				title = messages.format("lockError.title"),
				body = messages.format("lockError.body", result.lockError),
				urgency = NotificationUrgency.CRITICAL,
			)
			return
		}
		for (job in result.jobs) {
			if (!job.notify) continue
			when {
				job.errors > 0 && job.renewed > 0 -> notificationService.notify(
					title = messages.format("partialFailure.title", job.name),
					body = messages.format("partialFailure.body", job.renewed, job.errors),
					urgency = NotificationUrgency.CRITICAL,
				)

				job.errors > 0 -> notificationService.notify(
					title = messages.format("failure.title", job.name),
					body = messages.format("failure.body", job.errors),
					urgency = NotificationUrgency.CRITICAL,
				)

				job.renewed > 0 -> notificationService.notify(
					title = messages.format("complete.title", job.name),
					body = messages.format("complete.body", job.renewed),
					urgency = NotificationUrgency.NORMAL,
				)
			}
		}
		for (job in result.jobs) {
			if (!job.notify || job.newlyUnrecoverable == 0) continue
			notificationService.notify(
				title = messages.format("unrecoverable.title", job.name),
				body = messages.format("unrecoverable.body", job.newlyUnrecoverable),
				urgency = NotificationUrgency.CRITICAL,
			)
		}
		result.stalenessAlert?.let { alert ->
			notificationService.notify(
				title = messages.format("stale.title"),
				body = messages.format("stale.body", alert.daysWithoutSuccess),
				urgency = NotificationUrgency.CRITICAL,
			)
		}
	}

	/**
	 * Resolve the [key] entry of this bundle and interpolate [args] into its `{0}`, `{1}`, …
	 * placeholders via [MessageFormat].
	 *
	 * @param key The message key to look up.
	 * @param args Positional arguments substituted into the message's placeholders.
	 */
	private fun ResourceBundle.format(key: String, vararg args: Any): String =
		MessageFormat.format(getString(key), *args)

	companion object {
		/** Base name of the renewal-notification message bundle on the classpath. */
		private const val BUNDLE = "renewal-notifications"

		/**
		 * Bundle-lookup control that disables the default-locale fallback: a requested locale resolves
		 * to its own bundle (and parent chain) or the English base, never the machine default. This
		 * keeps a non-Czech default locale from leaking into an explicitly English- or other-locale run.
		 */
		private val NO_FALLBACK: ResourceBundle.Control =
			ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
	}
}
