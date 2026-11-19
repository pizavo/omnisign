package cz.pizavo.omnisign.domain.model.config

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

/**
 * Verifies how [algorithmConstraintDate] reads a date of an [AlgorithmConstraintsConfig], and how
 * [invalidDate] finds the first date it cannot read.
 */
class AlgorithmConstraintsConfigTest : FunSpec({

	test("algorithmConstraintDate reads a date in the form YYYY-MM-DD") {
		algorithmConstraintDate("2027-12-31") shouldBe LocalDate(2027, 12, 31)
		algorithmConstraintDate("2028-02-29") shouldBe LocalDate(2028, 2, 29)
		algorithmConstraintDate("0001-01-01") shouldBe LocalDate(1, 1, 1)
	}

	test("algorithmConstraintDate reads no other form, and no day the calendar lacks") {
		val texts = listOf(
			"2027", "31.12.2027", "2027/12/31", "2027-1-01", "27-12-31", "+12027-12-31", "-2027-12-31",
			" 2027-12-31", "2027-12-31 ", "2027-12-31T00:00", "2027-02-29", "2027-13-01", "",
		)
		for (text in texts) {
			withClue(text) { algorithmConstraintDate(text).shouldBeNull() }
		}
	}

	test("invalidDate is null when every date reads, and when there is none") {
		AlgorithmConstraintsConfig(
			policyUpdateDate = "2026-02-01",
			expirationDateOverrides = mapOf("SHA1" to "2012-08-01", "RSA:1900" to "2030-12-31"),
		).invalidDate().shouldBeNull()
		AlgorithmConstraintsConfig().invalidDate().shouldBeNull()
	}

	test("invalidDate names an update date it cannot read before any override") {
		AlgorithmConstraintsConfig(
			policyUpdateDate = "1.2.2026",
			expirationDateOverrides = mapOf("SHA1" to "2030"),
		).invalidDate() shouldBe ("policyUpdateDate" to "1.2.2026")
	}

	test("invalidDate names the first override it cannot read by its key") {
		AlgorithmConstraintsConfig(
			policyUpdateDate = "2026-02-01",
			expirationDateOverrides = linkedMapOf("SHA1" to "2012-08-01", "RSA:1900" to "2030", "SHA224" to "x"),
		).invalidDate() shouldBe ("expirationDateOverrides.RSA:1900" to "2030")
	}
})
