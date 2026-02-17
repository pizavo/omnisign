package cz.pizavo.omnisign

import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Basic smoke test for CLI setup.
 */
class OmnisignTest {
	@Test
	fun `CLI should be instantiable`() {
		val cli = Omnisign()
		assertNotNull(cli)
	}
}

