package cz.pizavo.omnisign.commands

import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Basic test for Validate command structure.
 */
class ValidateTest {
	@Test
	fun `validate command should be instantiable`() {
		val command = Validate()
		assertNotNull(command)
	}
}

