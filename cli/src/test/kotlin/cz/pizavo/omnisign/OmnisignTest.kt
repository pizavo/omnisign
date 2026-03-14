package cz.pizavo.omnisign

import com.github.ajalt.clikt.testing.test
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the root [Omnisign] command including global flags and version output.
 */
class OmnisignTest {
	@Test
	fun `CLI should be instantiable`() {
		val cli = Omnisign()
		assertNotNull(cli)
	}
	
	@Test
	fun `root command with no args prints help`() {
		val cli = Omnisign()
		val result = cli.test("")
		assertTrue(result.output.contains("omnisign") || result.output.contains("Usage"),
			"Should print help text")
	}
	
	@Test
	fun `root command registers global flags`() {
		val cli = Omnisign()
		val optionNames = cli.registeredOptions().flatMap { it.names }
		assertTrue("--json" in optionNames, "--json flag must be registered")
		assertTrue("--verbose" in optionNames, "--verbose flag must be registered")
		assertTrue("--quiet" in optionNames, "--quiet flag must be registered")
	}
	
	@Test
	fun `version option prints version`() {
		val cli = Omnisign()
		val result = cli.test("--version")
		assertTrue(result.output.isNotBlank(), "Version output should not be blank")
	}
	
	@Test
	fun `all subcommands are registered`() {
		val cli = Omnisign()
		val subcommandNames = cli.registeredSubcommands().map { it.commandName }
		assertTrue("sign" in subcommandNames)
		assertTrue("validate" in subcommandNames)
		assertTrue("timestamp" in subcommandNames)
		assertTrue("renew" in subcommandNames)
		assertTrue("algorithms" in subcommandNames)
		assertTrue("certificates" in subcommandNames)
		assertTrue("config" in subcommandNames)
		assertTrue("schedule" in subcommandNames)
	}
}
