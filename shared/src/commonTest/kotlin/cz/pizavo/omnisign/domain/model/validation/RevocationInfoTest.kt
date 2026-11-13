package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.value.DateFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verifies the per-signature revocation outcome and the conclusion that states it. The outcome is
 * judged as of the best-signature-time from the dates the checks give: a revocation dated by then
 * means the certificate was revoked, one dated later means it was revoked only afterwards, and a
 * good check settles the status only when it was issued at or after that time or was still current
 * then, before its next update, both with a few minutes' leeway for responders' clocks. The full
 * check list (rendered elsewhere) is what carries method, source and time detail.
 */
class RevocationInfoTest : FunSpec({

	val asOf = Instant.parse("2026-01-15T10:00:00Z")

	fun revocation(
		status: String = "GOOD",
		embedded: Boolean = false,
		sealed: Boolean = false,
		producedAt: Instant? = null,
		thisUpdate: Instant? = null,
		nextUpdate: Instant? = null,
		revocationDate: Instant? = null,
	) = RevocationInfo(
		method = "OCSP",
		status = status,
		revoked = status == "REVOKED",
		embedded = embedded,
		sealedByTimestamp = sealed,
		origin = if (embedded) "DSS_DICTIONARY" else "EXTERNAL",
		producedAt = producedAt,
		thisUpdate = thisUpdate,
		nextUpdate = nextUpdate,
		revocationDate = revocationDate,
	)

	test("no revocation data yields no outcome and no conclusion") {
		emptyList<RevocationInfo>().revocationOutcome(asOf).shouldBeNull()
		emptyList<RevocationInfo>().revocationConclusion(asOf).shouldBeNull()
	}

	test("a good check that vouches for the best-signature-time or later says the certificate was not revoked") {
		val tokens = listOf(revocation(status = "GOOD", thisUpdate = asOf + 5.minutes))

		tokens.revocationOutcome(asOf) shouldBe RevocationOutcome.NotRevoked
		tokens.revocationConclusion(asOf)!!.english() shouldContain "was not revoked as of"
	}

	test("a good check that vouches for exactly the best-signature-time settles the status") {
		listOf(revocation(status = "GOOD", thisUpdate = asOf)).revocationOutcome(asOf) shouldBe RevocationOutcome.NotRevoked
	}

	test("a good check without a this-update time counts from when it was produced") {
		listOf(revocation(status = "GOOD", producedAt = asOf + 5.minutes)).revocationOutcome(asOf) shouldBe
			RevocationOutcome.NotRevoked
	}

	test("a good check issued earlier that is still current at the best-signature-time says the certificate was not revoked") {
		val crl = revocation(status = "GOOD", thisUpdate = asOf - 3.hours, nextUpdate = asOf + 4.days)

		listOf(crl).revocationOutcome(asOf) shouldBe RevocationOutcome.NotRevoked
	}

	test("a good check whose next update passed before the best-signature-time leaves the status undetermined") {
		val tokens = listOf(
			revocation(status = "GOOD", embedded = true, sealed = true, thisUpdate = asOf - 8.days, nextUpdate = asOf - 1.days),
		)

		tokens.revocationOutcome(asOf) shouldBe RevocationOutcome.Undetermined
		tokens.revocationConclusion(asOf)!!.english() shouldContain "undetermined"
	}

	test("a good check whose next update passed just within the clock-skew leeway still covers the best-signature-time") {
		val leeway = RevocationInfo.CLOCK_SKEW_LEEWAY

		listOf(revocation(status = "GOOD", thisUpdate = asOf - 7.days, nextUpdate = asOf - leeway + 1.seconds))
			.revocationOutcome(asOf) shouldBe RevocationOutcome.NotRevoked
		listOf(revocation(status = "GOOD", thisUpdate = asOf - 7.days, nextUpdate = asOf - leeway))
			.revocationOutcome(asOf) shouldBe RevocationOutcome.Undetermined
	}

	test("a good check made moments before the best-signature-time, with no next update, still settles the status") {
		val leeway = RevocationInfo.CLOCK_SKEW_LEEWAY

		listOf(revocation(status = "GOOD", thisUpdate = asOf - 2.seconds)).revocationOutcome(asOf) shouldBe RevocationOutcome.NotRevoked
		listOf(revocation(status = "GOOD", thisUpdate = asOf - leeway)).revocationOutcome(asOf) shouldBe RevocationOutcome.NotRevoked
		listOf(revocation(status = "GOOD", thisUpdate = asOf - leeway - 1.seconds)).revocationOutcome(asOf) shouldBe
			RevocationOutcome.Undetermined
	}

	test("a good check issued earlier that names no next update leaves the status undetermined") {
		listOf(revocation(status = "GOOD", embedded = true, sealed = true, thisUpdate = asOf - 10.minutes)).revocationOutcome(asOf) shouldBe
			RevocationOutcome.Undetermined
	}

	test("a good check without a status time leaves the status undetermined, whatever its next update") {
		listOf(revocation(status = "GOOD")).revocationOutcome(asOf) shouldBe RevocationOutcome.Undetermined
		listOf(revocation(status = "GOOD", nextUpdate = asOf + 1.days)).revocationOutcome(asOf) shouldBe RevocationOutcome.Undetermined
	}

	test("an unknown check leaves the status undetermined") {
		listOf(revocation(status = "UNKNOWN", thisUpdate = asOf + 5.minutes)).revocationConclusion(asOf)!!.english() shouldContain
			"undetermined"
	}

	test("a revocation dated before the best-signature-time says the certificate was revoked") {
		val tokens = listOf(revocation(status = "REVOKED", thisUpdate = asOf + 1.days, revocationDate = asOf - 1.days))

		tokens.revocationOutcome(asOf) shouldBe RevocationOutcome.Revoked
		tokens.revocationConclusion(asOf)!!.english() shouldContain "was revoked as of"
	}

	test("a revocation dated exactly at the best-signature-time says the certificate was revoked") {
		listOf(revocation(status = "REVOKED", revocationDate = asOf)).revocationOutcome(asOf) shouldBe RevocationOutcome.Revoked
	}

	test("a revoked check without a revocation date says the certificate was revoked") {
		listOf(revocation(status = "REVOKED")).revocationOutcome(asOf) shouldBe RevocationOutcome.Revoked
	}

	test("a revocation dated after the best-signature-time says the certificate was revoked only later") {
		val revokedOn = Instant.parse("2026-06-10T08:00:00Z")
		val tokens = listOf(revocation(status = "REVOKED", thisUpdate = Instant.parse("2026-11-11T09:00:00Z"), revocationDate = revokedOn))

		tokens.revocationOutcome(asOf) shouldBe RevocationOutcome.RevokedLater(revokedOn)
		val text = tokens.revocationConclusion(asOf, DateFormat.ISO_8601)!!.english()
		text shouldContain "was not revoked as of 2026-01-15"
		text shouldContain "revoked later, on 2026-06-10"
	}

	test("of several later revocations the earliest is the one stated") {
		val first = Instant.parse("2026-03-01T08:00:00Z")
		val tokens = listOf(
			revocation(status = "REVOKED", revocationDate = Instant.parse("2026-06-10T08:00:00Z")),
			revocation(status = "REVOKED", revocationDate = first),
		)

		tokens.revocationOutcome(asOf) shouldBe RevocationOutcome.RevokedLater(first)
	}

	test("a revocation dated before the best-signature-time outweighs an embedded good check sealed at signing") {
		val embeddedGood = revocation(status = "GOOD", embedded = true, sealed = true, thisUpdate = asOf + 5.minutes)
		val onlineRevoked = revocation(status = "REVOKED", thisUpdate = asOf + 300.days, revocationDate = asOf - 2.days)

		listOf(embeddedGood, onlineRevoked).revocationOutcome(asOf) shouldBe RevocationOutcome.Revoked
	}

	test("an embedded good check and a revocation after the best-signature-time say it was revoked only later") {
		val revokedOn = asOf + 100.days
		val embeddedGood = revocation(status = "GOOD", embedded = true, sealed = true, thisUpdate = asOf + 5.minutes)
		val onlineRevoked = revocation(status = "REVOKED", thisUpdate = asOf + 300.days, revocationDate = revokedOn)

		listOf(onlineRevoked, embeddedGood).revocationOutcome(asOf) shouldBe RevocationOutcome.RevokedLater(revokedOn)
	}

	test("displayRows render their times in the requested date format") {
		val token = revocation(status = "GOOD", producedAt = Instant.parse("2026-01-15T09:50:00Z"))
			.copy(nextUpdate = Instant.parse("2026-01-22T09:50:00Z"))

		val iso = token.displayRows(DateFormat.ISO_8601).map { it.second.english() }
		val numeric = token.displayRows(DateFormat.DMY_DOT).map { it.second.english() }

		iso.count { it.matches(ISO_DATE_TIME) } shouldBe 2
		numeric.count { it.matches(DOTTED_DATE_TIME) } shouldBe 2
	}

	test("the conclusion renders its times in the requested date format") {
		val good = listOf(revocation(status = "GOOD", thisUpdate = asOf + 5.minutes))
		val revokedLater = listOf(revocation(status = "REVOKED", revocationDate = asOf + 100.days))

		good.revocationConclusion(asOf, DateFormat.ISO_8601)!!.english() shouldContain ISO_DATE
		good.revocationConclusion(asOf, DateFormat.DMY_DOT)!!.english() shouldContain DOTTED_DATE
		ISO_DATE.findAll(revokedLater.revocationConclusion(asOf, DateFormat.ISO_8601)!!.english()).count() shouldBe 2
		DOTTED_DATE.findAll(revokedLater.revocationConclusion(asOf, DateFormat.DMY_DOT)!!.english()).count() shouldBe 2
	}
})

/** `2026-01-15`, wherever in the world the test runs. */
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** `15.01.2026`, wherever in the world the test runs. */
private val DOTTED_DATE = Regex("""\d{2}\.\d{2}\.\d{4}""")

/** A full row value in ISO style, e.g. `2026-01-15, 10:50:00 (+01:00)`. */
private val ISO_DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}, .*""")

/** A full row value in dotted style, e.g. `15.01.2026, 10:50:00 (+01:00)`. */
private val DOTTED_DATE_TIME = Regex("""\d{2}\.\d{2}\.\d{4}, .*""")
