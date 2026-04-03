package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.GetDocumentTimestampInfoUseCase
import cz.pizavo.omnisign.ui.model.TimestampDialogState
import cz.pizavo.omnisign.ui.model.TimestampType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

/**
 * Unit tests for [TimestampViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimestampViewModelTest : FunSpec({

	val archivingRepository = mockk<ArchivingRepository>()
	val configRepository = mockk<ConfigRepository>()
	val extendUseCase = ExtendDocumentUseCase(archivingRepository)
	val getTimestampInfoUseCase = GetDocumentTimestampInfoUseCase(archivingRepository)
	val testDispatcher = StandardTestDispatcher()

	val noTimestamps = DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = false)
	val hasDocTs = DocumentTimestampInfo(hasDocumentTimestamp = true, containsLtData = true)
	val hasLtOnly = DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = true)

	val appConfig = AppConfig(
		global = GlobalConfig(),
		profiles = emptyMap(),
	)

	beforeEach {
		io.mockk.clearMocks(archivingRepository, configRepository)
		coEvery { configRepository.getCurrentConfig() } returns appConfig
		coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns noTimestamps.right()
		Dispatchers.setMain(testDispatcher)
	}

	afterEach {
		Dispatchers.resetMain()
	}

	fun buildVm() = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, ioDispatcher = testDispatcher)

	test("initial state is Idle") {
		buildVm().state.value.shouldBeInstanceOf<TimestampDialogState.Idle>()
	}

	test("open transitions to Ready with Archival Timestamp as default") {
		runTest(testDispatcher) {
			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.timestampType shouldBe TimestampType.ARCHIVAL_TIMESTAMP
			state.disabledTypes shouldBe emptySet()
			state.outputPath shouldContain "-extended"
		}
	}

	test("open disables Signature Timestamp when document has document timestamp") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns hasDocTs.right()

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.disabledTypes shouldBe setOf(TimestampType.SIGNATURE_TIMESTAMP)
			state.timestampType shouldBe TimestampType.ARCHIVAL_TIMESTAMP
		}
	}

	test("onDocumentChanged pre-fetches timestamp info for the given file") {
		runTest(testDispatcher) {
			val vm = buildVm()
			vm.onDocumentChanged("/tmp/test-doc.pdf")
			advanceUntilIdle()

			coVerify(exactly = 1) { archivingRepository.getDocumentTimestampInfo("/tmp/test-doc.pdf") }
		}
	}

	test("open falls back to fresh fetch when no prior onDocumentChanged was called") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns
					ArchivingError.ExtensionFailed(message = "corrupt file").left()

			val vm = buildVm()
			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.disabledTypes shouldBe emptySet()
		}
	}

	test("updateState modifies Ready state") {
		runTest(testDispatcher) {
			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.timestampType shouldBe TimestampType.SIGNATURE_TIMESTAMP
		}
	}

	test("extend transitions to Success on successful extension") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputFile = "/tmp/signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			state.outputFile shouldBe "/tmp/signed-extended.pdf"
			state.newLevel shouldBe "PAdES-BASELINE-LTA"
		}
	}

	test("extend transitions to Error on generic failure") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.ExtensionFailed(
						message = "Extension failed",
						details = "TSA unavailable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Error>()
			state.message shouldBe "Extension failed"
			state.details shouldBe "TSA unavailable"
		}
	}

	test("extend to LT with revocation error shows RevocationWarning when document has no LT data") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						message = "Failed to obtain revocation information",
						details = "OCSP responder unreachable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }
			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.RevocationWarning>()
			state.warnings.any { it.contains("revocation", ignoreCase = true) } shouldBe true
		}
	}

	test("extend to LT with revocation error shows Error when document already contains LT data") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns hasLtOnly.right()
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						message = "Failed to obtain revocation information",
						details = "OCSP responder unreachable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }
			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Error>()
			state.message shouldContain "Revocation data could not be refreshed"
			state.details shouldContain "degrade"
		}
	}

	test("extend to LTA with revocation error shows Error not RevocationWarning") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						message = "Failed to obtain revocation information",
						details = "OCSP unreachable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Error>()
		}
	}

	test("acceptRevocationWarning retries extend at B-T level") {
		runTest(testDispatcher) {
			var callCount = 0
			coEvery { archivingRepository.extendDocument(any()) } answers {
				callCount++
				if (callCount == 1) {
					ArchivingError.RevocationInfoError(
						message = "Failed to obtain revocation information",
					).left()
				} else {
					ArchivingResult(
						outputFile = "/tmp/signed-extended.pdf",
						newSignatureLevel = "PADES_BASELINE_T",
					).right()
				}
			}

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }
			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.RevocationWarning>()

			vm.acceptRevocationWarning()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			state.newLevel shouldBe "PADES_BASELINE_T"
			callCount shouldBe 2

			coVerify(exactly = 2) { archivingRepository.extendDocument(any()) }
		}
	}

	test("abortAfterRevocationWarning returns to Ready state") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						message = "Failed to obtain revocation information",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }
			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.RevocationWarning>()

			vm.abortAfterRevocationWarning()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.timestampType shouldBe TimestampType.SIGNATURE_TIMESTAMP
		}
	}

	test("dismiss resets state to Idle") {
		runTest(testDispatcher) {
			val vm = buildVm()
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.dismiss()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Idle>()
		}
	}

	test("pendingRenewalOffer is populated after LTA extension with addToRenewalJob checked") {
		runTest(testDispatcher) {
			val ltaConfig = AppConfig(
				global = GlobalConfig(),
				profiles = mapOf("prod" to ProfileConfig(name = "prod")),
				activeProfile = "prod",
			)
			coEvery { configRepository.getCurrentConfig() } returns ltaConfig
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputFile = "/tmp/signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(addToRenewalJob = true) }
			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			val offer = vm.pendingRenewalOffer.value
			offer.shouldNotBeNull()
			offer.outputFile shouldBe "/tmp/signed-extended.pdf"
			offer.availableProfiles shouldBe listOf("prod")
		}
	}

	test("pendingRenewalOffer is null when addToRenewalJob is not checked") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputFile = "/tmp/signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}

	test("pendingRenewalOffer is null for Signature Timestamp even with addToRenewalJob checked") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputFile = "/tmp/signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LT",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP, addToRenewalJob = true) }
			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}

	test("dismissRenewalOffer clears pending offer") {
		runTest(testDispatcher) {
			val ltaConfig = AppConfig(global = GlobalConfig())
			coEvery { configRepository.getCurrentConfig() } returns ltaConfig
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputFile = "/tmp/signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(addToRenewalJob = true) }
			vm.extend()
			advanceUntilIdle()

			vm.pendingRenewalOffer.value.shouldNotBeNull()
			vm.dismissRenewalOffer()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}
})



