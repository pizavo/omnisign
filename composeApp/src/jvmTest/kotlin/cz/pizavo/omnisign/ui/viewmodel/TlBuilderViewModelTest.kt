package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import cz.pizavo.omnisign.ui.model.ServiceEditState
import cz.pizavo.omnisign.ui.model.TlBuilderDialogState
import cz.pizavo.omnisign.ui.model.TspEditState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Unit tests for [TlBuilderViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TlBuilderViewModelTest : FunSpec({

	val compilerPort: TrustedListCompilerPort = mockk()
	val testDispatcher = StandardTestDispatcher()

	beforeTest {
		clearMocks(compilerPort)
		Dispatchers.setMain(testDispatcher)
	}

	afterTest {
		Dispatchers.resetMain()
	}

	test("open transitions to Editing state") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Idle>()

		vm.open()

		vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Editing>()
	}

	test("open with default output dir pre-fills outputPath") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open("/some/dir")

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.outputPath shouldBe "/some/dir/"
	}

	test("dismiss resets to Idle") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.dismiss()

		vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Idle>()
	}

	test("addTsp appends empty TSP to editing state") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.addTsp()
		vm.addTsp()

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.tsps shouldHaveSize 2
	}

	test("removeTsp removes TSP at index") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.addTsp()
		vm.updateState { it.copy(tsps = it.tsps.mapIndexed { i, t -> if (i == 0) t.copy(name = "First") else t }) }
		vm.addTsp()
		vm.removeTsp(0)

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.tsps shouldHaveSize 1
		editing.tsps[0].name shouldBe ""
	}

	test("addService appends empty service to specified TSP") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.addTsp()
		vm.addService(0)
		vm.addService(0)

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.tsps[0].services shouldHaveSize 2
	}

	test("removeService removes service from specified TSP") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.addTsp()
		vm.addService(0)
		vm.addService(0)
		vm.removeService(0, 0)

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.tsps[0].services shouldHaveSize 1
	}

	test("compile with empty name shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.compile()

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.error shouldBe "Name is required."
	}

	test("compile with empty TSPs shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState {
			it.copy(
				name = "test",
				territory = "CZ",
				schemeOperatorName = "Operator",
				outputPath = "/out.xml",
			)
		}
		vm.compile()

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.error shouldBe "At least one Trust Service Provider is required."
	}

	test("compile with incomplete service shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState {
			it.copy(
				name = "test",
				territory = "CZ",
				schemeOperatorName = "Operator",
				outputPath = "/out.xml",
				tsps = listOf(
					TspEditState(
						name = "TSP1",
						services = listOf(ServiceEditState(name = "Svc1")),
					)
				),
			)
		}
		vm.compile()

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.error shouldBe "TSP 'TSP1', Service 'Svc1': type identifier is required."
	}

	test("compile with valid data and successful compilation transitions to Success") {
		runTest(testDispatcher) {
			every { compilerPort.compileTo(any(), any()) } returns Unit.right()

			val vm = TlBuilderViewModel(compilerPort, testDispatcher)
			vm.open()
			vm.updateState { validEditingState() }
			vm.compile()

			advanceUntilIdle()

			val success = vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Success>()
			success.outputFile shouldBe "/output/test.xml"
			val tlConfig = success.tlConfig
			tlConfig.shouldNotBeNull()
			tlConfig.name shouldBe "test"

			verify(exactly = 1) { compilerPort.compileTo(any<CustomTrustedListDraft>(), eq("/output/test.xml")) }
		}
	}

	test("compile with registerAfterCompile=false returns null tlConfig") {
		runTest(testDispatcher) {
			every { compilerPort.compileTo(any(), any()) } returns Unit.right()

			val vm = TlBuilderViewModel(compilerPort, testDispatcher)
			vm.open()
			vm.updateState { validEditingState().copy(registerAfterCompile = false) }
			vm.compile()

			advanceUntilIdle()

			val success = vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Success>()
			success.tlConfig.shouldBeNull()
		}
	}

	test("compile with compiler failure transitions to Error") {
		runTest(testDispatcher) {
			every { compilerPort.compileTo(any(), any()) } returns ConfigurationError.SaveFailed(
				message = "Write failed"
			).left()

			val vm = TlBuilderViewModel(compilerPort, testDispatcher)
			vm.open()
			vm.updateState { validEditingState() }
			vm.compile()

			advanceUntilIdle()

			val error = vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Error>()
			error.message shouldBe "Write failed"
		}
	}

	test("compile without compiler port transitions to Error") {
		val vm = TlBuilderViewModel(compilerPort = null, testDispatcher)
		vm.open()
		vm.updateState { validEditingState() }
		vm.compile()

		val error = vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Error>()
		error.message shouldBe "Trusted list compilation is not available on this platform."
	}

	test("updateState clears error when field changes") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.compile()
		val withError = vm.state.value as TlBuilderDialogState.Editing
		withError.error.shouldNotBeNull()

		vm.updateState { it.copy(name = "new-name", error = null) }
		val cleared = vm.state.value as TlBuilderDialogState.Editing
		cleared.error.shouldBeNull()
		cleared.name shouldBe "new-name"
	}
})

/**
 * Build a fully-populated [TlBuilderDialogState.Editing] state that passes validation.
 */
private fun validEditingState(): TlBuilderDialogState.Editing = TlBuilderDialogState.Editing(
	name = "test",
	territory = "CZ",
	schemeOperatorName = "Test Operator",
	outputPath = "/output/test.xml",
	registerAfterCompile = true,
	tsps = listOf(
		TspEditState(
			name = "TSP One",
			services = listOf(
				ServiceEditState(
					name = "CA Service",
					typeIdentifier = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
					status = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
					certificatePath = "/path/to/cert.pem",
				)
			),
		)
	),
)




