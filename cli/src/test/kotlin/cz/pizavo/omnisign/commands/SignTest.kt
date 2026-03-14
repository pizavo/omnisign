package cz.pizavo.omnisign.commands

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioral tests for the [Sign] command verifying stdout/stderr output
 * and exit codes with mocked dependencies.
 */
class SignTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signingRepository: SigningRepository = mockk()
    private val configRepository: ConfigRepository = mockk()

    private val defaultConfig = AppConfig(
        global = GlobalConfig(
            defaultHashAlgorithm = HashAlgorithm.SHA256,
            defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
        )
    )

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            modules(module {
                single { SignDocumentUseCase(signingRepository) }
                single { configRepository }
                single<PasswordCallback> { mockk() }
            })
        }
        coEvery { configRepository.getCurrentConfig() } returns defaultConfig
    }

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

    @Test
    fun `successful sign outputs result to stdout`() {
        val input = tmp.newFile("input.pdf")
        val output = tmp.newFile("output.pdf")

        coEvery { signingRepository.signDocument(any()) } returns SigningResult(
            outputFile = output.absolutePath,
            signatureId = "sig-123",
            signatureLevel = "PAdES-BASELINE-B",
        ).right()

        val result = Omnisign().test(listOf("sign", "-f", input.absolutePath, "-o", output.absolutePath))

        assertTrue(result.output.contains("SIGNING RESULT"), "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.output.contains("sig-123"), "Should print signature ID")
        assertTrue(result.output.contains("PAdES-BASELINE-B"), "Should print signature level")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `sign error exits with code 1 and prints to stderr`() {
        val input = tmp.newFile("input.pdf")
        val output = tmp.newFile("output.pdf")

        coEvery { signingRepository.signDocument(any()) } returns SigningError.SigningFailed(
            message = "Token not found",
            details = "No PKCS#11 token available",
        ).left()

        val result = Omnisign().test(listOf("sign", "-f", input.absolutePath, "-o", output.absolutePath))

        assertEquals(1, result.statusCode, "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.stderr.contains("Token not found"), "stderr was: ${result.stderr}")
    }

    @Test
    fun `sign with --json outputs JSON on success`() {
        val input = tmp.newFile("input.pdf")
        val output = tmp.newFile("output.pdf")

        coEvery { signingRepository.signDocument(any()) } returns SigningResult(
            outputFile = output.absolutePath,
            signatureId = "sig-json",
            signatureLevel = "PAdES-BASELINE-T",
        ).right()

        val result = Omnisign().test(listOf("--json", "sign", "-f", input.absolutePath, "-o", output.absolutePath))

        assertTrue(result.output.contains("\"success\""), "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.output.contains("sig-json"), "JSON output should contain signature ID")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `sign with --json outputs JSON on error with exit code 1`() {
        val input = tmp.newFile("input.pdf")
        val output = tmp.newFile("output.pdf")

        coEvery { signingRepository.signDocument(any()) } returns SigningError.SigningFailed(
            message = "Certificate expired",
        ).left()

        val result = Omnisign().test(listOf("--json", "sign", "-f", input.absolutePath, "-o", output.absolutePath))

        assertTrue(result.output.contains("\"success\""), "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.output.contains("Certificate expired"), "JSON output should contain error message")
        assertEquals(1, result.statusCode)
    }
}
