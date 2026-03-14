package cz.pizavo.omnisign.commands

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
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
 * Behavioral tests for the [Validate] command verifying stdout/stderr output,
 * exit codes, and JSON mode with mocked dependencies.
 */
class ValidateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val validationRepository: ValidationRepository = mockk()
    private val configRepository: ConfigRepository = mockk()

    private val defaultConfig = AppConfig(
        global = GlobalConfig(
            defaultHashAlgorithm = HashAlgorithm.SHA256,
            defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
        )
    )

    private val sampleReport = ValidationReport(
        documentName = "test.pdf",
        validationTime = "2026-03-14T10:00:00Z",
        overallResult = ValidationResult.VALID,
        signatures = listOf(
            SignatureValidationResult(
                signatureId = "sig-1",
                indication = ValidationIndication.TOTAL_PASSED,
                signedBy = "Test Signer",
                signatureLevel = "PAdES-BASELINE-T",
                signatureTime = "2026-03-14T09:00:00Z",
                certificate = CertificateInfo(
                    subjectDN = "CN=Test",
                    issuerDN = "CN=CA",
                    serialNumber = "1234",
                    validFrom = "2025-01-01",
                    validTo = "2027-01-01",
                ),
            )
        ),
    )

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            modules(module {
                single { ValidateDocumentUseCase(validationRepository) }
                single { configRepository }
                single<PasswordCallback> { mockk() }
            })
        }
        coEvery { configRepository.getCurrentConfig() } returns defaultConfig
    }

    @Test
    fun `validate command should be instantiable`() {
        val command = Validate()
        assertNotNull(command)
    }

    @Test
    fun `successful validation prints report to stdout`() {
        val input = tmp.newFile("signed.pdf")
        coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()

        val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))

        assertTrue(result.output.contains("VALIDATION REPORT"), "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.output.contains("test.pdf"), "Should print document name")
        assertTrue(result.output.contains("VALID"), "Should print overall result")
        assertTrue(result.output.contains("Test Signer"), "Should print signer name")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `validation error exits with code 1`() {
        val input = tmp.newFile("bad.pdf")
        coEvery { validationRepository.validateDocument(any()) } returns ValidationError.ValidationFailed(
            message = "Document is corrupted",
        ).left()

        val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))

        assertEquals(1, result.statusCode, "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.stderr.contains("Document is corrupted"), "stderr was: ${result.stderr}")
    }

    @Test
    fun `validate --json outputs structured JSON on success`() {
        val input = tmp.newFile("signed.pdf")
        coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()

        val result = Omnisign().test(listOf("--json", "validate", "-f", input.absolutePath))

        assertTrue(result.output.contains("\"success\""), "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.output.contains("\"overallResult\""), "JSON should contain overallResult")
        assertTrue(result.output.contains("test.pdf"), "JSON should contain document name")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `validate --json outputs JSON error with exit code 1`() {
        val input = tmp.newFile("bad.pdf")
        coEvery { validationRepository.validateDocument(any()) } returns ValidationError.ValidationFailed(
            message = "File not a PDF",
        ).left()

        val result = Omnisign().test(listOf("--json", "validate", "-f", input.absolutePath))

        assertTrue(result.output.contains("\"success\""), "stdout was: ${result.output}\nstderr was: ${result.stderr}")
        assertTrue(result.output.contains("File not a PDF"), "JSON should contain error message")
        assertEquals(1, result.statusCode)
    }
}
