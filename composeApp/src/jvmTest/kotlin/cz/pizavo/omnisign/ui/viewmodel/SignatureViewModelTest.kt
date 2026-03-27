package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import cz.pizavo.omnisign.ui.model.SignaturePanelState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Instant

/**
 * Unit tests for [SignatureViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignatureViewModelTest : FunSpec({

    val validationRepository = mockk<ValidationRepository>()
    val configRepository = mockk<ConfigRepository>()
    val useCase = ValidateDocumentUseCase(validationRepository)
    val testDispatcher = StandardTestDispatcher()

    val sampleReport = ValidationReport(
        documentName = "test.pdf",
        validationTime = Instant.parse("2026-03-27T10:00:00Z"),
        overallResult = ValidationResult.VALID,
        signatures = listOf(
            SignatureValidationResult(
                signatureId = "sig-1",
                indication = ValidationIndication.TOTAL_PASSED,
                signedBy = "Test Signer",
                signatureLevel = "PAdES-BASELINE-T",
                signatureTime = Instant.parse("2026-03-27T09:00:00Z"),
                certificate = CertificateInfo(
                    subjectDN = "CN=Test",
                    issuerDN = "CN=CA",
                    serialNumber = "ABCD",
                    validFrom = Instant.parse("2025-01-01T00:00:00Z"),
                    validTo = Instant.parse("2027-01-01T00:00:00Z"),
                ),
            )
        ),
    )

    beforeTest {
        clearMocks(validationRepository, configRepository)
        coEvery { configRepository.getCurrentConfig() } returns AppConfig()
        Dispatchers.setMain(testDispatcher)
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("initial state is Idle with no document") {
        val vm = SignatureViewModel(useCase, configRepository, testDispatcher)

        vm.state.value.shouldBeInstanceOf<SignaturePanelState.Idle>()
        (vm.state.value as SignaturePanelState.Idle).hasDocument shouldBe false
    }

    test("onDocumentChanged sets Idle with hasDocument true") {
        val vm = SignatureViewModel(useCase, configRepository, testDispatcher)
        vm.onDocumentChanged("/path/to/file.pdf")

        val state = vm.state.value.shouldBeInstanceOf<SignaturePanelState.Idle>()
        state.hasDocument shouldBe true
    }

    test("onDocumentChanged with null resets to Idle without document") {
        val vm = SignatureViewModel(useCase, configRepository, testDispatcher)
        vm.onDocumentChanged("/path/to/file.pdf")
        vm.onDocumentChanged(null)

        val state = vm.state.value.shouldBeInstanceOf<SignaturePanelState.Idle>()
        state.hasDocument shouldBe false
    }

    test("loadSignatures is no-op when no document is loaded") {
        runTest(testDispatcher) {
            val vm = SignatureViewModel(useCase, configRepository, testDispatcher)
            vm.loadSignatures()
            advanceUntilIdle()

            vm.state.value.shouldBeInstanceOf<SignaturePanelState.Idle>()
        }
    }

    test("loadSignatures transitions to Loaded on success") {
        runTest(testDispatcher) {
            coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()

            val vm = SignatureViewModel(useCase, configRepository, testDispatcher)
            vm.onDocumentChanged("/path/to/signed.pdf")
            vm.loadSignatures()
            advanceUntilIdle()

            val state = vm.state.value.shouldBeInstanceOf<SignaturePanelState.Loaded>()
            state.report.documentName shouldBe "test.pdf"
            state.report.signatures.size shouldBe 1
        }
    }

    test("loadSignatures transitions to Error on failure") {
        runTest(testDispatcher) {
            coEvery { validationRepository.validateDocument(any()) } returns
                    ValidationError.ValidationFailed(message = "Corrupted PDF").left()

            val vm = SignatureViewModel(useCase, configRepository, testDispatcher)
            vm.onDocumentChanged("/path/to/bad.pdf")
            vm.loadSignatures()
            advanceUntilIdle()

            val state = vm.state.value.shouldBeInstanceOf<SignaturePanelState.Error>()
            state.message shouldBe "Corrupted PDF"
        }
    }

    test("onDocumentChanged resets Loaded state back to Idle") {
        runTest(testDispatcher) {
            coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()

            val vm = SignatureViewModel(useCase, configRepository, testDispatcher)
            vm.onDocumentChanged("/first.pdf")
            vm.loadSignatures()
            advanceUntilIdle()
            vm.state.value.shouldBeInstanceOf<SignaturePanelState.Loaded>()

            vm.onDocumentChanged("/second.pdf")
            val state = vm.state.value.shouldBeInstanceOf<SignaturePanelState.Idle>()
            state.hasDocument shouldBe true
        }
    }

    test("exportReportText returns null when not in Loaded state") {
        val vm = SignatureViewModel(useCase, configRepository, testDispatcher)

        vm.exportReportText().shouldBeNull()
    }

    test("exportReportText returns formatted text when Loaded") {
        runTest(testDispatcher) {
            coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()

            val vm = SignatureViewModel(useCase, configRepository, testDispatcher)
            vm.onDocumentChanged("/path/to/file.pdf")
            vm.loadSignatures()
            advanceUntilIdle()

            val text = vm.exportReportText().shouldNotBeNull()
            text shouldContain "test.pdf"
            text shouldContain "VALID"
            text shouldContain "Test Signer"
            text shouldContain "PAdES-BASELINE-T"
            text shouldContain "CN=Test"
        }
    }
})
