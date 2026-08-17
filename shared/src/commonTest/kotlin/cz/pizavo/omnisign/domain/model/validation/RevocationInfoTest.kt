package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.value.DateFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Instant

/**
 * Verifies the per-signature revocation conclusion and the representative-token pick used to phrase
 * it. The conclusion is a bare statement of the outcome as of the best-signature-time; the full
 * token list (rendered elsewhere) is what carries method/source/time detail.
 */
class RevocationInfoTest : FunSpec({

	val asOf = Instant.parse("2026-01-15T10:00:00Z")

	fun revocation(
		status: String = "GOOD",
		revoked: Boolean = false,
		embedded: Boolean = false,
		sealed: Boolean = false,
		producedAt: Instant? = null,
	) = RevocationInfo(
		method = "OCSP",
		status = status,
		revoked = revoked,
		embedded = embedded,
		sealedByTimestamp = sealed,
		origin = if (embedded) "DSS_DICTIONARY" else "EXTERNAL",
		producedAt = producedAt,
	)

	test("no revocation data yields no conclusion") {
		emptyList<RevocationInfo>().revocationConclusion(asOf).shouldBeNull()
	}

	test("a good token states the certificate was not revoked as of the time") {
		val text = listOf(revocation(status = "GOOD")).revocationConclusion(asOf)?.english()
		text shouldContain "was not revoked as of"
	}

	test("a revoked token states the certificate was revoked") {
		listOf(revocation(status = "REVOKED", revoked = true)).revocationConclusion(asOf)!!.english() shouldContain "was revoked as of"
	}

	test("an unknown token states the status could not be determined") {
		listOf(revocation(status = "UNKNOWN")).revocationConclusion(asOf)!!.english() shouldContain "undetermined"
	}

	test("the sealed embedded token speaks for the conclusion, not a later online one") {
		val embeddedGood = revocation(
			status = "GOOD", embedded = true, sealed = true,
			producedAt = Instant.parse("2026-01-15T09:50:00Z"),
		)
		val onlineRevoked = revocation(
			status = "REVOKED", revoked = true, embedded = false, sealed = false,
			producedAt = Instant.parse("2026-01-15T10:00:00Z"),
		)

		val tokens = listOf(onlineRevoked, embeddedGood)

		tokens.signingTimeRepresentative() shouldBe embeddedGood
		tokens.revocationConclusion(asOf)!!.english() shouldContain "was not revoked"
	}

	test("with no embedded token the online one represents the conclusion") {
		val online = revocation(status = "GOOD", embedded = false)
		listOf(online).signingTimeRepresentative() shouldBe online
	}

	test("displayRows render their times in the requested date format") {
		val token = revocation(status = "GOOD", producedAt = Instant.parse("2026-01-15T09:50:00Z"))
			.copy(nextUpdate = Instant.parse("2026-01-22T09:50:00Z"))

		val iso = token.displayRows(DateFormat.ISO_8601).map { it.second.english() }
		val numeric = token.displayRows(DateFormat.DMY_DOT).map { it.second.english() }

		iso.count { it.matches(ISO_DATE_TIME) } shouldBe 2
		numeric.count { it.matches(DOTTED_DATE_TIME) } shouldBe 2
	}

	test("the conclusion renders its time in the requested date format") {
		val tokens = listOf(revocation(status = "GOOD"))

		tokens.revocationConclusion(asOf, DateFormat.ISO_8601)!!.english() shouldContain ISO_DATE
		tokens.revocationConclusion(asOf, DateFormat.DMY_DOT)!!.english() shouldContain DOTTED_DATE
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
