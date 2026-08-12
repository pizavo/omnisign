package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewBatchResult
import cz.pizavo.omnisign.domain.model.result.RenewJobResult
import cz.pizavo.omnisign.domain.model.result.StalenessAlert
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import java.util.Locale

/**
 * Verifies [RenewalNotifier] fires exactly one correctly-branded notification per notifying job
 * along the partial-failure / failure / completion ladder, raises the run-wide lock-error and
 * staleness notifications independently of the per-job opt-in, and stays silent for dry-runs,
 * opted-out jobs, and jobs where nothing was renewed. A fixed locale is injected so the asserted text
 * is independent of the machine's default locale, and a Czech-locale notifier confirms the bundle is
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

	test("raises a critical notification for newly terminal documents, alongside the job outcome") {
		notifier.notify(
			RenewBatchResult(
				jobs = listOf(
					RenewJobResult(
						name = "job",
						renewed = 1,
						unrecoverable = 3,
						newlyUnrecoverable = 2,
						notify = true,
					),
				),
			),
		)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("no longer be preserved") },
				match { it.contains("2") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
		verify(exactly = 1) { notificationService.notify(match { it.contains("complete") }, any(), eq(NotificationUrgency.NORMAL)) }
		verify(exactly = 2) { notificationService.notify(any(), any(), any()) }
	}

	test("says nothing about terminal documents that were already reported") {
		notifier.notify(
			RenewBatchResult(
				jobs = listOf(
					RenewJobResult(name = "job", unrecoverable = 4, newlyUnrecoverable = 0, notify = true),
				),
			),
		)
		verify(exactly = 0) { notificationService.notify(any(), any(), any()) }
	}

	test("respects the per-job opt-out for terminal documents") {
		notifier.notify(
			RenewBatchResult(
				jobs = listOf(
					RenewJobResult(name = "job", unrecoverable = 1, newlyUnrecoverable = 1, notify = false),
				),
			),
		)
		verify(exactly = 0) { notificationService.notify(any(), any(), any()) }
	}

	test("sends nothing about terminal documents on a dry-run") {
		notifier.notify(
			RenewBatchResult(
				dryRun = true,
				jobs = listOf(
					RenewJobResult(name = "job", unrecoverable = 1, newlyUnrecoverable = 1, notify = true),
				),
			),
		)
		verify(exactly = 0) { notificationService.notify(any(), any(), any()) }
	}

	test("sends a single critical staleness notification when renewal has gone too long without success") {
		notifier.notify(RenewBatchResult(stalenessAlert = StalenessAlert(daysWithoutSuccess = 21)))
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("OmniSign") && it.contains("needs attention") },
				match { it.contains("21") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
		verify(exactly = 1) { notificationService.notify(any(), any(), any()) }
	}

	test("fires the staleness notification on top of a per-job failure") {
		notifier.notify(
			RenewBatchResult(
				jobs = listOf(RenewJobResult(name = "job", renewed = 0, errors = 2, notify = true)),
				stalenessAlert = StalenessAlert(daysWithoutSuccess = 30),
			),
		)
		verify(exactly = 1) { notificationService.notify(match { it.contains("Renewal failed") }, any(), eq(NotificationUrgency.CRITICAL)) }
		verify(exactly = 1) { notificationService.notify(match { it.contains("needs attention") }, any(), eq(NotificationUrgency.CRITICAL)) }
		verify(exactly = 2) { notificationService.notify(any(), any(), any()) }
	}

	test("renders the Czech staleness title and body when the locale is Czech") {
		czechNotifier.notify(RenewBatchResult(stalenessAlert = StalenessAlert(daysWithoutSuccess = 21)))
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("Obnova vyžaduje pozornost") },
				match { it.contains("bez úspěšné obnovy") && it.contains("21") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
	}

	test("raises a critical notification for runs that keep being interrupted") {
		notifier.runsKeepBeingInterrupted(3)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("OmniSign") && it.contains("keeps being interrupted") },
				match { it.contains("3") && it.contains("before they could finish") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
	}

	test("renders the Czech interrupted-run title and body when the locale is Czech") {
		czechNotifier.runsKeepBeingInterrupted(4)
		verify(exactly = 1) {
			notificationService.notify(
				match { it.contains("Obnova se opakovaně přerušuje") },
				match { it.contains("4") && it.contains("než mohlo doběhnout") },
				eq(NotificationUrgency.CRITICAL),
			)
		}
	}
})
