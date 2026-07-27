package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import cz.pizavo.omnisign.ui.model.AddressEditState
import cz.pizavo.omnisign.ui.model.ErrorMessage
import cz.pizavo.omnisign.ui.model.ServiceEditState
import cz.pizavo.omnisign.ui.model.TlBuilderDialogState
import cz.pizavo.omnisign.ui.model.TlValidationError
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
import io.mockk.slot
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

	// Destination the save dialog would return; compilation writes the XML here.
	val outPath = "/output/test.xml"

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

	test("open with default output dir pre-fills outputDirectory") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open("/some/dir")

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.outputDirectory shouldBe "/some/dir"
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
		vm.compile(outPath)

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.error shouldBe TlValidationError.NameRequired
	}

	test("compile without a scheme name shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState { validEditingState().copy(schemeName = "") }
		vm.compile(outPath)

		(vm.state.value as TlBuilderDialogState.Editing).error shouldBe TlValidationError.SchemeNameRequired
	}

	test("compile without a scheme information URI shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState { validEditingState().copy(schemeInformationUri = "") }
		vm.compile(outPath)

		(vm.state.value as TlBuilderDialogState.Editing).error shouldBe
			TlValidationError.SchemeInformationUriRequired
	}

	test("compile with an incomplete operator address shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState { validEditingState().copy(schemeOperatorAddress = testAddress.copy(country = "")) }
		vm.compile(outPath)

		(vm.state.value as TlBuilderDialogState.Editing).error shouldBe
			TlValidationError.SchemeOperatorAddressRequired
	}

	test("compile without a provider information URL names the provider") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState {
			val valid = validEditingState()
			valid.copy(tsps = valid.tsps.map { tsp -> tsp.copy(infoUrl = "") })
		}
		vm.compile(outPath)

		(vm.state.value as TlBuilderDialogState.Editing).error shouldBe
			TlValidationError.TspInfoUrlRequired("TSP One")
	}

	test("compile with an incomplete provider address names the provider") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState {
			val valid = validEditingState()
			valid.copy(tsps = valid.tsps.map { tsp -> tsp.copy(address = testAddress.copy(street = "")) })
		}
		vm.compile(outPath)

		(vm.state.value as TlBuilderDialogState.Editing).error shouldBe
			TlValidationError.TspAddressRequired("TSP One")
	}

	test("the compiled draft carries the scheme fields and both addresses") {
		runTest(testDispatcher) {
			val draft = slot<CustomTrustedListDraft>()
			every { compilerPort.compileTo(capture(draft), any()) } returns Unit.right()
			val vm = TlBuilderViewModel(compilerPort, testDispatcher)
			vm.open()
			vm.updateState { validEditingState() }

			vm.compile(outPath)
			advanceUntilIdle()

			val compiled = draft.captured
			compiled.schemeName shouldBe "Test Scheme"
			compiled.schemeInformationUri shouldBe "https://omnisign.test/tl"
			compiled.schemeOperatorAddress?.streetAddress shouldBe "Technicka 2"
			compiled.schemeOperatorAddress?.electronicAddress shouldBe "mailto:tl@omnisign.test"
			compiled.trustServiceProviders.single().infoUrl shouldBe "https://tsp.omnisign.test"
			compiled.trustServiceProviders.single().address?.locality shouldBe "Praha"
		}
	}

	test("compile with empty TSPs shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState {
			it.copy(
				name = "test",
				territory = "CZ",
				schemeOperatorName = "Operator",
				schemeName = "Scheme",
				schemeInformationUri = "https://omnisign.test/tl",
				schemeOperatorAddress = testAddress,
			)
		}
		vm.compile(outPath)

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.error shouldBe TlValidationError.TspRequired
	}

	test("compile with incomplete service shows validation error") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.updateState {
			it.copy(
				name = "test",
				territory = "CZ",
				schemeOperatorName = "Operator",
				schemeName = "Scheme",
				schemeInformationUri = "https://omnisign.test/tl",
				schemeOperatorAddress = testAddress,
				tsps = listOf(
					TspEditState(
						name = "TSP1",
						infoUrl = "https://tsp.test",
						address = testAddress,
						services = listOf(ServiceEditState(name = "Svc1")),
					)
				),
			)
		}
		vm.compile(outPath)

		val editing = vm.state.value as TlBuilderDialogState.Editing
		editing.error shouldBe TlValidationError.ServiceTypeRequired("TSP1", "Svc1")
	}

	test("compile with valid data and successful compilation transitions to Success") {
		runTest(testDispatcher) {
			every { compilerPort.compileTo(any(), any()) } returns Unit.right()

			val vm = TlBuilderViewModel(compilerPort, testDispatcher)
			vm.open()
			vm.updateState { validEditingState() }
			vm.compile(outPath)

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
			vm.compile(outPath)

			advanceUntilIdle()

			val success = vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Success>()
			success.tlConfig.shouldBeNull()
		}
	}

	test("compile with compiler failure transitions to Error") {
		runTest(testDispatcher) {
			every { compilerPort.compileTo(any(), any()) } returns ConfigurationError.SaveFailed(
				LocalizableText.Literal("Write failed")
			).left()

			val vm = TlBuilderViewModel(compilerPort, testDispatcher)
			vm.open()
			vm.updateState { validEditingState() }
			vm.compile(outPath)

			advanceUntilIdle()

			val error = vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Error>()
			error.content shouldBe ErrorMessage.Domain(LocalizableText.Literal("Write failed"), null)
		}
	}

	test("compile without compiler port transitions to Error") {
		val vm = TlBuilderViewModel(compilerPort = null, testDispatcher)
		vm.open()
		vm.updateState { validEditingState() }
		vm.compile(outPath)

		val error = vm.state.value.shouldBeInstanceOf<TlBuilderDialogState.Error>()
		error.content shouldBe ErrorMessage.CompilerUnavailable
	}

	test("updateState clears error when field changes") {
		val vm = TlBuilderViewModel(compilerPort, testDispatcher)
		vm.open()
		vm.compile(outPath)
		val withError = vm.state.value as TlBuilderDialogState.Editing
		withError.error.shouldNotBeNull()

		vm.updateState { it.copy(name = "new-name", error = null) }
		val cleared = vm.state.value as TlBuilderDialogState.Editing
		cleared.error.shouldBeNull()
		cleared.name shouldBe "new-name"
	}
})

/**
 * The postal and electronic address ETSI TS 119612 requires of the scheme operator and of every
 * provider; the same value serves both in these specs.
 */
private val testAddress = AddressEditState(
	street = "Technicka 2",
	locality = "Praha",
	country = "CZ",
	electronicAddress = "mailto:tl@omnisign.test",
)

/**
 * Build a fully-populated [TlBuilderDialogState.Editing] state that passes validation.
 */
private fun validEditingState(): TlBuilderDialogState.Editing = TlBuilderDialogState.Editing(
	name = "test",
	territory = "CZ",
	schemeOperatorName = "Test Operator",
	schemeName = "Test Scheme",
	schemeInformationUri = "https://omnisign.test/tl",
	schemeOperatorAddress = testAddress,
	registerAfterCompile = true,
	tsps = listOf(
		TspEditState(
			name = "TSP One",
			infoUrl = "https://tsp.omnisign.test",
			address = testAddress,
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




