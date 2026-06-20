package cz.pizavo.omnisign.domain.model.value

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Verifies the regional numeric [DateFormat] patterns and that the time/offset suffix is preserved
 * across formats.
 */
class DateFormatTest : FunSpec({

	val utc = TimeZone.UTC
	val instant = Instant.parse("2026-03-14T10:05:09Z")

	test("each DateFormat renders the date portion as expected") {
		instant.formatDate(utc, DateFormat.SYSTEM) shouldBe "Sat, 14 March 2026"
		instant.formatDate(utc, DateFormat.DMY_SLASH) shouldBe "14/03/2026"
		instant.formatDate(utc, DateFormat.DMY_DOT) shouldBe "14.03.2026"
		instant.formatDate(utc, DateFormat.MDY_SLASH) shouldBe "03/14/2026"
		instant.formatDate(utc, DateFormat.ISO_8601) shouldBe "2026-03-14"
	}

	test("formatDateTime keeps the time and UTC offset regardless of the date format") {
		instant.formatDateTime(utc, DateFormat.DMY_DOT) shouldBe "14.03.2026, 10:05:09 (Z)"
		instant.formatDateTime(utc, DateFormat.ISO_8601) shouldBe "2026-03-14, 10:05:09 (Z)"
	}
})
