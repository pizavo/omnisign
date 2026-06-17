package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewalRunError
import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Round-trip and missing-file tests for [FileRenewalRunRecordStore].
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
})
