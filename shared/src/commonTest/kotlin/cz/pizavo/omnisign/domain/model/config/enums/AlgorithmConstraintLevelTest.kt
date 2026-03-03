package cz.pizavo.omnisign.domain.model.config.enums

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [AlgorithmConstraintLevel] properties and exhaustiveness.
 */
class AlgorithmConstraintLevelTest {
	
	@Test
	fun `all entries have non-blank descriptions`() {
		AlgorithmConstraintLevel.entries.forEach { level ->
			assertTrue(level.description.isNotBlank(), "$level description must not be blank")
		}
	}
	
	@Test
	fun `entries cover all four DSS Level counterparts`() {
		val names = AlgorithmConstraintLevel.entries.map { it.name }
		assertEquals(4, names.size)
		assertEquals(listOf("FAIL", "WARN", "INFORM", "IGNORE"), names)
	}
}

