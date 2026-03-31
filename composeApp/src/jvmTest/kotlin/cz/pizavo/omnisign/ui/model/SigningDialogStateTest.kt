package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [SigningDialogState.Ready] computed properties.
 */
class SigningDialogStateTest : FunSpec({

	test("effectiveSignatureLevel returns B-B when neither checkbox is checked") {
		val state = SigningDialogState.Ready(
			addSignatureTimestamp = false,
			addArchivalTimestamp = false,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
	}

	test("effectiveSignatureLevel returns B-LT when only signature timestamp is checked") {
		val state = SigningDialogState.Ready(
			addSignatureTimestamp = true,
			addArchivalTimestamp = false,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("effectiveSignatureLevel returns B-LTA when both checkboxes are checked") {
		val state = SigningDialogState.Ready(
			addSignatureTimestamp = true,
			addArchivalTimestamp = true,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("effectiveSignatureLevel returns B-LTA when archival is checked regardless of signature") {
		val state = SigningDialogState.Ready(
			addSignatureTimestamp = false,
			addArchivalTimestamp = true,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("effectiveAddTimestamp is false when neither checkbox is checked") {
		val state = SigningDialogState.Ready(
			addSignatureTimestamp = false,
			addArchivalTimestamp = false,
		)
		state.effectiveAddTimestamp shouldBe false
	}

	test("effectiveAddTimestamp is true when signature timestamp is checked") {
		val state = SigningDialogState.Ready(
			addSignatureTimestamp = true,
			addArchivalTimestamp = false,
		)
		state.effectiveAddTimestamp shouldBe true
	}

	test("effectiveAddTimestamp is true when archival timestamp is checked") {
		val state = SigningDialogState.Ready(
			addSignatureTimestamp = false,
			addArchivalTimestamp = true,
		)
		state.effectiveAddTimestamp shouldBe true
	}
})

