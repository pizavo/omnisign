package cz.pizavo.omnisign.konsist

import io.kotest.assertions.fail

/**
 * The convention violations that the code already had when a rule was introduced, or that stay on purpose, read
 * from `known-violations.txt`.
 *
 * The file works as a ratchet. A violation that is not listed fails the check, so new code follows the conventions,
 * and a listed violation that no longer occurs fails it too, so the list only shrinks and never hides a later
 * regression at the same place. Blank lines and lines starting with `#` are ignored; the same line may appear more
 * than once when a file has several matching violations, such as overloads.
 *
 * @param lines The lines of `known-violations.txt`.
 */
class KnownViolations(lines: List<String>) {
	private val counts: Map<String, Int> =
		lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.groupingBy { it }.eachCount()

	/**
	 * Fails when the violations [found] for [rule] differ from the listed ones, naming the lines to fix or add and
	 * the lines to remove.
	 *
	 * @param rule The rule that was checked.
	 * @param found The violations the check found, in the format of `known-violations.txt`.
	 */
	fun verify(rule: ConventionRule, found: List<String>) {
		val foundCounts = found.groupingBy { it }.eachCount()
		val listedCounts = counts.filterKeys { it.startsWith("${rule.id} ") }
		val added = foundCounts.surplusOver(listedCounts)
		val gone = listedCounts.surplusOver(foundCounts)
		if (added.isEmpty() && gone.isEmpty()) return

		fail(
			buildString {
				appendLine("Convention: ${rule.convention}.")
				if (added.isNotEmpty()) {
					appendLine("New violations; fix them, or add the line to $FILE when one has to stay:")
					added.forEach { appendLine("+ $it") }
				}
				if (gone.isNotEmpty()) {
					appendLine("Listed violations that no longer occur; remove the line from $FILE:")
					gone.forEach { appendLine("- $it") }
				}
			},
		)
	}

	/**
	 * Returns each line as many times as this map counts it beyond [other], sorted.
	 */
	private fun Map<String, Int>.surplusOver(other: Map<String, Int>): List<String> =
		flatMap { (line, count) -> List((count - (other[line] ?: 0)).coerceAtLeast(0)) { line } }.sorted()

	/**
	 * Locates and loads the list.
	 */
	companion object {
		/**
		 * Where the list lives in the repository, for failure messages.
		 */
		const val FILE = "konsistTest/src/test/resources/known-violations.txt"

		/**
		 * Loads the list from the test resources.
		 *
		 * @return The listed violations.
		 */
		fun load(): KnownViolations {
			val resource = KnownViolations::class.java.getResource("/known-violations.txt")
				?: error("$FILE is missing from the test resources")
			return KnownViolations(resource.readText().lines())
		}
	}
}
