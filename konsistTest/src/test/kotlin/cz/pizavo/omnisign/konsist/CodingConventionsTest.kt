package cz.pizavo.omnisign.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec

/**
 * Checks every module's Kotlin sources against the coding conventions in `AGENTS.md`, one test per
 * [ConventionRule], and compares the violations with [KnownViolations].
 */
class CodingConventionsTest : FunSpec({
	val checks = ConventionChecks(Konsist.scopeFromProject())
	val known = KnownViolations.load()

	ConventionRule.entries.forEach { rule ->
		test(rule.convention) {
			known.verify(rule, checks.violationsOf(rule))
		}
	}
})
