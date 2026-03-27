package cz.pizavo.omnisign.domain.model.value

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Verifies [InstantFormatter] output format for both date-time and date-only representations.
 */
class InstantFormatterTest : FunSpec({

    val utc = TimeZone.UTC

    test("formatDateTime produces human-readable date-time with UTC offset") {
        val instant = Instant.parse("2026-03-14T10:05:09Z")

        instant.formatDateTime(utc) shouldBe "Sat, 14 March 2026, 10:05:09 (Z)"
    }

    test("formatDateTime does not zero-pad day") {
        val instant = Instant.parse("2026-01-02T03:04:05Z")

        instant.formatDateTime(utc) shouldBe "Fri, 2 January 2026, 03:04:05 (Z)"
    }

    test("formatDate produces human-readable date") {
        val instant = Instant.parse("2025-12-31T23:59:59Z")

        instant.formatDate(utc) shouldBe "Wed, 31 December 2025"
    }

    test("formatDateTime respects positive UTC offset") {
        val cet = TimeZone.of("Europe/Prague")
        val instant = Instant.parse("2026-07-14T10:00:00Z")

        val result = instant.formatDateTime(cet)

        result shouldBe "Tue, 14 July 2026, 12:00:00 (+02:00)"
    }

    test("formatDateTime respects negative UTC offset") {
        val nyc = TimeZone.of("America/New_York")
        val instant = Instant.parse("2026-03-14T10:00:00Z")

        val result = instant.formatDateTime(nyc)

        result shouldBe "Sat, 14 March 2026, 06:00:00 (-04:00)"
    }

    test("formatDate crosses day boundary in positive offset timezone") {
        val cet = TimeZone.of("Europe/Prague")
        val instant = Instant.parse("2026-03-14T23:30:00Z")

        instant.formatDate(cet) shouldBe "Sun, 15 March 2026"
    }

    test("formatDateTime epoch zero") {
        val instant = Instant.fromEpochSeconds(0)

        instant.formatDateTime(utc) shouldBe "Thu, 1 January 1970, 00:00:00 (Z)"
    }
})
