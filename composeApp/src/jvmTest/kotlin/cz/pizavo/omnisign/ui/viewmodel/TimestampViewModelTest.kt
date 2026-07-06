package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.GetDocumentTimestampInfoUseCase
import cz.pizavo.omnisign.ui.model.ErrorMessage
import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
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
	val hasSigTsOnly = DocumentTimestampInfo(
		hasDocumentTimestamp = false,
		containsLtData = false,
		hasSignatureTimestamp = true,
	)

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

	fun sampleDoc(filePath: String? = "/tmp/signed.pdf", name: String = "signed.pdf"): PdfDocumentInfo =
		PdfDocumentInfo(name = name, data = ByteArray(0), pageCount = 1, filePath = filePath)

	// Destination the save dialog would return; extension writes the produced bytes here.
	val extendedPath = "/tmp/signed-extended.pdf"

	fun buildVm() = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, ioDispatcher = testDispatcher)

	test("initial state is Idle") {
		buildVm().state.value.shouldBeInstanceOf<TimestampDialogState.Idle>()
	}

	test("open transitions to Ready with Archival Timestamp as default") {
		runTest(testDispatcher) {
			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.timestampType shouldBe TimestampType.ARCHIVAL_TIMESTAMP
			state.currentLevel shouldBe SignatureLevel.PADES_BASELINE_B
			state.unavailableTypes shouldBe emptySet()
			state.suggestedName shouldContain "-extended"
		}
	}

	test("open marks Signature Timestamp unavailable when document has a document timestamp") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns hasDocTs.right()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.unavailableTypes shouldBe setOf(TimestampType.SIGNATURE_TIMESTAMP)
			state.timestampType shouldBe TimestampType.ARCHIVAL_TIMESTAMP
			state.currentLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
		}
	}

	test("open defaults to the revocation option at B-T level for a timestamped document") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns hasSigTsOnly.right()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.currentLevel shouldBe SignatureLevel.PADES_BASELINE_T
			state.timestampType shouldBe TimestampType.SIGNATURE_TIMESTAMP
			state.unavailableTypes shouldBe emptySet()
		}
	}

	test("onDocumentChanged pre-fetches timestamp info for the given file") {
		runTest(testDispatcher) {
			val doc = sampleDoc(filePath = "/tmp/test-doc.pdf", name = "test-doc.pdf")
			val vm = buildVm()
			vm.onDocumentChanged(doc)
			advanceUntilIdle()

			coVerify(exactly = 1) { archivingRepository.getDocumentTimestampInfo(doc.data) }
		}
	}

	test("open falls back to fresh fetch when no prior onDocumentChanged was called") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns
					ArchivingError.ExtensionFailed(text = LocalizableText.Literal("corrupt file")).left()

			val vm = buildVm()
			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.unavailableTypes shouldBe emptySet()
		}
	}

	test("updateState modifies Ready state") {
		runTest(testDispatcher) {
			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
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
						outputBytes = ByteArray(0), outputName = "signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()
			vm.state.value.shouldBeInstanceOf<TimestampDialogState.AwaitingSave>()

			vm.saveExtendedDocument(extendedPath)
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
						text = LocalizableText.Literal("Extension failed"),
						details = "TSA unavailable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Error>()
			state.content shouldBe ErrorMessage.Domain(LocalizableText.Literal("Extension failed"), "TSA unavailable")
		}
	}

	test("extend to LT with revocation error shows RevocationWarning when document has no LT data") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						text = LocalizableText.Literal("Failed to obtain revocation information"),
						details = "OCSP responder unreachable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }
			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.RevocationWarning>()
			state.warnings.any { it.english().contains("revocation", ignoreCase = true) } shouldBe true
		}
	}

	test("extend to LT with revocation error shows Error when document already contains LT data") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.getDocumentTimestampInfo(any()) } returns hasLtOnly.right()
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						text = LocalizableText.Literal("Failed to obtain revocation information"),
						details = "OCSP responder unreachable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }
			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Error>()
			state.content.shouldBeInstanceOf<ErrorMessage.RevocationRefreshFailed>()
		}
	}

	test("extend to LTA with revocation error shows Error not RevocationWarning") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						text = LocalizableText.Literal("Failed to obtain revocation information"),
						details = "OCSP unreachable",
					).left()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
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
						text = LocalizableText.Literal("Failed to obtain revocation information"),
					).left()
				} else {
					ArchivingResult(
						outputBytes = ByteArray(0), outputName = "signed-extended.pdf",
						newSignatureLevel = "PADES_BASELINE_T",
					).right()
				}
			}

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP) }
			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.RevocationWarning>()

			vm.acceptRevocationWarning()
			advanceUntilIdle()
			vm.state.value.shouldBeInstanceOf<TimestampDialogState.AwaitingSave>()

			vm.saveExtendedDocument(extendedPath)
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			state.newLevel shouldBe "PADES_BASELINE_T"
			callCount shouldBe 2

			coVerify(exactly = 2) { archivingRepository.extendDocument(any()) }
		}
	}

	test("cancelSave discards the extended bytes and restores Ready without writing") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputBytes = ByteArray(0), outputName = "signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.AwaitingSave>()
			vm.cancelSave()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
		}
	}

	test("abortAfterRevocationWarning returns to Ready state") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.RevocationInfoError(
						text = LocalizableText.Literal("Failed to obtain revocation information"),
					).left()

			val vm = buildVm()
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
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
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
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
						outputBytes = ByteArray(0), outputName = "signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(addToRenewalJob = true) }
			vm.extend()
			advanceUntilIdle()
			vm.saveExtendedDocument(extendedPath)
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
						outputBytes = ByteArray(0), outputName = "signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()
			vm.saveExtendedDocument(extendedPath)
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}

	test("pendingRenewalOffer is null for Signature Timestamp even with addToRenewalJob checked") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputBytes = ByteArray(0), outputName = "signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LT",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(timestampType = TimestampType.SIGNATURE_TIMESTAMP, addToRenewalJob = true) }
			vm.extend()
			advanceUntilIdle()
			vm.saveExtendedDocument(extendedPath)
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
						outputBytes = ByteArray(0), outputName = "signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = TimestampViewModel(extendUseCase, getTimestampInfoUseCase, configRepository, assigner, testDispatcher)
			vm.onDocumentChanged(sampleDoc())
			advanceUntilIdle()

			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(addToRenewalJob = true) }
			vm.extend()
			advanceUntilIdle()
			vm.saveExtendedDocument(extendedPath)
			advanceUntilIdle()

			vm.pendingRenewalOffer.value.shouldNotBeNull()
			vm.dismissRenewalOffer()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}
})



