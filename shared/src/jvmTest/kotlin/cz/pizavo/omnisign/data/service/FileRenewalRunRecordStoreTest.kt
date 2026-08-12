package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewalRunError
import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.writeText
import kotlin.time.Instant

/**
 * Round-trip, missing-file and upgrade tests for [FileRenewalRunRecordStore].
 *
 * The upgrade case matters because the record survives an update in place: every existing
 * installation has a `last-renewal.json` written before the interrupted-run fields existed, and a
 * record that failed to parse would be read as "no history" — silently resetting the last-success
 * timestamp and the failure counters the scheduler's whole reporting rests on.
 */
class FileRenewalRunRecordStoreTest : FunSpec({

	test("load returns null when no record file exists") {
		val store = FileRenewalRunRecordStore(tempdir().resolve("last-renewal.json").toPath())
		store.load().shouldBeNull()
	}

	test("save then load round-trips the record, creating parent directories") {
		val file = tempdir().resolve("nested/last-renewal.json").toPath()
		val store = FileRenewalRunRecordStore(file)
		val record = RenewalRunRecord(
			lastRunAt = Instant.fromEpochSeconds(1_700_000_000),
			outcome = RenewalRunOutcome.COMPLETED_WITH_ERRORS,
			checked = 5,
			renewed = 2,
			skipped = 2,
			errors = 1,
			errorDetails = listOf(RenewalRunError(path = "/docs/a.pdf", message = "tsa down")),
			warnings = listOf("weak digest algorithm"),
			lastSuccessAt = Instant.fromEpochSeconds(1_600_000_000),
			failuresSinceSuccess = 1,
		)

		store.save(record)

		store.load() shouldBe record
	}

	test("a record written before the interrupted-run fields existed still loads") {
		val file = tempdir().resolve("last-renewal.json").toPath()
		file.writeText(
			"""
			{
				"lastRunAt": "2023-11-14T22:13:20Z",
				"outcome": "COMPLETED_WITH_ERRORS",
				"checked": 5,
				"renewed": 2,
				"skipped": 2,
				"errors": 1,
				"unrecoverable": 0,
				"unrecoverablePaths": [],
				"errorDetails": [],
				"warnings": [],
				"jobs": [],
				"lastSuccessAt": "2020-09-13T12:26:40Z",
				"failuresSinceSuccess": 1
			}
			""".trimIndent()
		)

		val record = FileRenewalRunRecordStore(file).load().shouldNotBeNull()

		record.outcome shouldBe RenewalRunOutcome.COMPLETED_WITH_ERRORS
		record.lastSuccessAt shouldBe Instant.fromEpochSeconds(1_600_000_000)
		record.failuresSinceSuccess shouldBe 1
		record.runStartedAt.shouldBeNull()
		record.consecutiveInterruptions shouldBe 0
	}

	test("a record carrying an unknown field from a newer version still loads") {
		val file = tempdir().resolve("last-renewal.json").toPath()
		file.writeText(
			"""
			{
				"lastRunAt": "2023-11-14T22:13:20Z",
				"outcome": "SUCCESS",
				"somethingAddedLater": "ignore me"
			}
			""".trimIndent()
		)

		FileRenewalRunRecordStore(file).load().shouldNotBeNull().outcome shouldBe RenewalRunOutcome.SUCCESS
	}
})
