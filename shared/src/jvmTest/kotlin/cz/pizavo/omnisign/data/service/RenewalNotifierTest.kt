package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import cz.pizavo.omnisign.domain.model.result.RenewJobResult
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import java.util.Locale

/**
 * Verifies [RenewalNotifier] fires exactly one correctly-branded notification per notifying job
 * along the partial-failure / failure / completion ladder, and stays silent for dry-runs, opted-out
 * jobs, and jobs where nothing was renewed. A fixed locale is injected so the asserted text is
 * independent of the machine's default locale, and a Czech-locale notifier confirms the bundle is
 * actually resolved per locale.
 */
class RenewalNotifierTest : FunSpec({

	val notificationService: OsNotificationService = mockk(relaxed = true)
	val notifier = RenewalNotifier(notificationService) { Locale.ENGLISH }
	val czechNotifier = RenewalNotifier(notificationService) { Locale.forLanguageTag("cs") }

	beforeTest { clearMocks(notificationService) }

	test("sends a critical partial-failure notification when some files renewed and others errored") {
		notifier.notify(
			RenewBatchResult(jobs = listOf(RenewJobResult(name = "job", renewed = 2, errors = 1, notify = true))),
		)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("OmniSign") && it.contains("partial failure") && it.contains("job") },
				any(),
				eq(NotificationUrgency.CRITICAL),
			)
		}
	}

	test("sends a critical failure notification when only errors occurred") {
		notifier.notify(
			RenewBatchResult(jobs = listOf(RenewJobResult(name = "job", renewed = 0, errors = 3, notify = true))),
		)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("Renewal failed") },
				any(),
				eq(NotificationUrgency.CRITICAL),
			)
		}
	}

	test("sends a single critical lock-error notification when the renewal lock could not be acquired") {
		notifier.notify(RenewBatchResult(lockError = "lock file unwritable"))
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("OmniSign") && it.contains("could not start") },
				match { it.contains("lock file unwritable") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
		verify(exactly = 1) { notificationService.notify(any(), any(), any()) }
	}

	test("sends a normal completion notification when files renewed cleanly") {
		notifier.notify(
			RenewBatchResult(jobs = listOf(RenewJobResult(name = "job", renewed = 4, errors = 0, notify = true))),
		)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("Renewal complete") },
				any(),
				eq(NotificationUrgency.NORMAL),
			)
		}
	}

	test("sends nothing when every file was skipped") {
		notifier.notify(
			RenewBatchResult(
				skipped = 5,
				jobs = listOf(RenewJobResult(name = "job", renewed = 0, errors = 0, notify = true)),
			),
		)
		verify(exactly = 0) { notificationService.notify(any(), any(), any()) }
	}

	test("sends nothing for a job that opted out of notifications") {
		notifier.notify(
			RenewBatchResult(jobs = listOf(RenewJobResult(name = "job", renewed = 2, errors = 1, notify = false))),
		)
		verify(exactly = 0) { notificationService.notify(any(), any(), any()) }
	}

	test("sends nothing for a dry-run") {
		notifier.notify(
			RenewBatchResult(
				dryRun = true,
				jobs = listOf(RenewJobResult(name = "job", renewed = 2, errors = 0, notify = true)),
			),
		)
		verify(exactly = 0) { notificationService.notify(any(), any(), any()) }
	}

	test("notifies each notifying job independently") {
		notifier.notify(
			RenewBatchResult(
				jobs = listOf(
					RenewJobResult(name = "a", renewed = 1, errors = 0, notify = true),
					RenewJobResult(name = "b", renewed = 0, errors = 1, notify = true),
					RenewJobResult(name = "c", renewed = 1, errors = 0, notify = false),
				),
			),
		)
		verify(exactly = 1) { notificationService.notify(match { it.contains("(a)") }, any(), eq(NotificationUrgency.NORMAL)) }
		verify(exactly = 1) { notificationService.notify(match { it.contains("(b)") }, any(), eq(NotificationUrgency.CRITICAL)) }
		verify(exactly = 0) { notificationService.notify(match { it.contains("(c)") }, any(), any()) }
	}

	test("renders Czech title and body for a clean completion when the locale is Czech") {
		czechNotifier.notify(
			RenewBatchResult(jobs = listOf(RenewJobResult(name = "job", renewed = 4, errors = 0, notify = true))),
		)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("Obnova dokončena") && it.contains("job") },
				match { it.contains("Úspěšně obnoveno") },
				eq(NotificationUrgency.NORMAL),
			)
		}
	}

	test("renders the Czech failure title and body for an all-errors job") {
		czechNotifier.notify(
			RenewBatchResult(jobs = listOf(RenewJobResult(name = "job", renewed = 0, errors = 2, notify = true))),
		)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("Obnova selhala") },
				match { it.contains("dlouhodobá platnost") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
	}

	test("renders the Czech lock-error title and body when the locale is Czech") {
		czechNotifier.notify(RenewBatchResult(lockError = "soubor zámku nelze zapsat"))
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("Obnovu nelze spustit") },
				match { it.contains("zámek obnovy") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
	}
})
