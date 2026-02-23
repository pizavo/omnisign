package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertIs

/**
 * Unit tests for [DssArchivingRepository].
 *
 * Tests cover:
 * - Missing input file returns [ArchivingError.ExtensionFailed].
 * - Missing TSA configuration returns [ArchivingError.ExtensionFailed].
 * - B-B target level returns [ArchivingError.ExtensionFailed].
 * - [DssArchivingRepository.needsArchivalRenewal] returns [ArchivingError.ExtensionFailed] for a missing file.
 */
class DssArchivingRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val configRepository: ConfigRepository = mockk()
    private val credentialStore: CredentialStore = mockk()

    private val repository = DssArchivingRepository(configRepository, credentialStore)

    private fun configWithoutTsa() = AppConfig(
        global = GlobalConfig(
            defaultHashAlgorithm = HashAlgorithm.SHA256,
            defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA
        )
    )

    @Test
    fun `extendDocument returns ExtensionFailed when input file does not exist`() = runBlocking<Unit> {
        coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()

        val result = repository.extendDocument(
            ArchivingParameters(
                inputFile = "/nonexistent/signed.pdf",
                outputFile = tmpFolder.newFile("out.pdf").absolutePath
            )
        )

        assertIs<arrow.core.Either.Left<ArchivingError.ExtensionFailed>>(result)
    }

    @Test
    fun `extendDocument returns ExtensionFailed when no TSA is configured`() = runBlocking<Unit> {
        coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()

        val result = repository.extendDocument(
            ArchivingParameters(
                inputFile = tmpFolder.newFile("signed.pdf").absolutePath,
                outputFile = tmpFolder.newFile("out.pdf").absolutePath,
                targetLevel = SignatureLevel.PADES_BASELINE_T
            )
        )

        assertIs<arrow.core.Either.Left<ArchivingError.ExtensionFailed>>(result)
    }

    @Test
    fun `extendDocument returns ExtensionFailed when target level is B-B`() = runBlocking<Unit> {
        coEvery { configRepository.getCurrentConfig() } returns configWithoutTsa()

        val result = repository.extendDocument(
            ArchivingParameters(
                inputFile = tmpFolder.newFile("signed.pdf").absolutePath,
                outputFile = tmpFolder.newFile("out.pdf").absolutePath,
                targetLevel = SignatureLevel.PADES_BASELINE_B
            )
        )

        assertIs<arrow.core.Either.Left<ArchivingError.ExtensionFailed>>(result)
    }

    @Test
    fun `needsArchivalRenewal returns ExtensionFailed for a non-existent file`() = runBlocking<Unit> {
        val result = repository.needsArchivalRenewal("/nonexistent/doc.pdf")

        assertIs<arrow.core.Either.Left<ArchivingError.ExtensionFailed>>(result)
    }
}
