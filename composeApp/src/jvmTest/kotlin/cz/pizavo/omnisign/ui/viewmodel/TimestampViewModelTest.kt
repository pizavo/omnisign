package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.ui.model.TimestampDialogState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Unit tests for [TimestampViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimestampViewModelTest : FunSpec({

	val archivingRepository = mockk<ArchivingRepository>()
	val configRepository = mockk<ConfigRepository>()
	val extendUseCase = ExtendDocumentUseCase(archivingRepository)
	val testDispatcher = StandardTestDispatcher()

	val appConfig = AppConfig(
		global = GlobalConfig(),
		profiles = emptyMap(),
	)

	beforeEach {
		coEvery { configRepository.getCurrentConfig() } returns appConfig
		Dispatchers.setMain(testDispatcher)
	}

	afterEach {
		Dispatchers.resetMain()
	}

	test("initial state is Idle") {
		val vm = TimestampViewModel(extendUseCase, configRepository, testDispatcher)
		vm.state.value.shouldBeInstanceOf<TimestampDialogState.Idle>()
	}

	test("open transitions to Ready with default target level") {
		runTest(testDispatcher) {
			val vm = TimestampViewModel(extendUseCase, configRepository, testDispatcher)
			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.targetLevel shouldBe SignatureLevel.PADES_BASELINE_T
			state.outputPath shouldContain "-extended"
		}
	}

	test("updateState modifies Ready state") {
		runTest(testDispatcher) {
			val vm = TimestampViewModel(extendUseCase, configRepository, testDispatcher)
			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(targetLevel = SignatureLevel.PADES_BASELINE_LTA) }

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Ready>()
			state.targetLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
		}
	}

	test("extend transitions to Success on successful extension") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingResult(
						outputFile = "/tmp/signed-extended.pdf",
						newSignatureLevel = "PAdES-BASELINE-T",
					).right()

			val vm = TimestampViewModel(extendUseCase, configRepository, testDispatcher)
			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Success>()
			state.outputFile shouldBe "/tmp/signed-extended.pdf"
			state.newLevel shouldBe "PAdES-BASELINE-T"
		}
	}

	test("extend transitions to Error on failure") {
		runTest(testDispatcher) {
			coEvery { archivingRepository.extendDocument(any()) } returns
					ArchivingError.ExtensionFailed(
						message = "Extension failed",
						details = "TSA unavailable",
					).left()

			val vm = TimestampViewModel(extendUseCase, configRepository, testDispatcher)
			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.extend()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<TimestampDialogState.Error>()
			state.message shouldBe "Extension failed"
			state.details shouldBe "TSA unavailable"
		}
	}

	test("dismiss resets state to Idle") {
		runTest(testDispatcher) {
			val vm = TimestampViewModel(extendUseCase, configRepository, testDispatcher)
			vm.open("/tmp/signed.pdf")
			advanceUntilIdle()

			vm.dismiss()

			vm.state.value.shouldBeInstanceOf<TimestampDialogState.Idle>()
		}
	}

	test("EXTENDABLE_LEVELS excludes PADES_BASELINE_B") {
		TimestampViewModel.EXTENDABLE_LEVELS shouldBe listOf(
			SignatureLevel.PADES_BASELINE_T,
			SignatureLevel.PADES_BASELINE_LT,
			SignatureLevel.PADES_BASELINE_LTA,
		)
	}
})



