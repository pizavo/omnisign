package cz.pizavo.omnisign.commands

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the [Sign] command structure and option parsing.
 */
class SignTest {

    @Test
    fun `sign command should be instantiable`() {
        val command = Sign()
        assertNotNull(command)
    }

    @Test
    fun `sign command name should be 'sign'`() {
        val command = Sign()
        assertEquals("sign", command.commandName)
    }

    @Test
    fun `sign command registered options should include required file and output`() {
        val command = Sign()
        val optionNames = command.registeredOptions().flatMap { it.names }
        assertTrue("--file" in optionNames, "--file option must be registered")
        assertTrue("-f" in optionNames, "-f short option must be registered")
        assertTrue("--output" in optionNames, "--output option must be registered")
        assertTrue("-o" in optionNames, "-o short option must be registered")
    }

    @Test
    fun `sign command registered options should include signature metadata options`() {
        val command = Sign()
        val optionNames = command.registeredOptions().flatMap { it.names }
        assertTrue("--reason" in optionNames)
        assertTrue("--location" in optionNames)
        assertTrue("--contact" in optionNames)
        assertTrue("--certificate" in optionNames)
    }

    @Test
    fun `sign command registered options should include timestamp and profile overrides`() {
        val command = Sign()
        val optionNames = command.registeredOptions().flatMap { it.names }
        assertTrue("--no-timestamp" in optionNames)
        assertTrue("--profile" in optionNames)
    }

    @Test
    fun `sign command registered options should include visible signature options`() {
        val command = Sign()
        val optionNames = command.registeredOptions().flatMap { it.names }
        assertTrue("--visible" in optionNames)
        assertTrue("--vis-page" in optionNames)
        assertTrue("--vis-x" in optionNames)
        assertTrue("--vis-y" in optionNames)
        assertTrue("--vis-width" in optionNames)
        assertTrue("--vis-height" in optionNames)
        assertTrue("--vis-text" in optionNames)
        assertTrue("--vis-image" in optionNames)
    }
}

