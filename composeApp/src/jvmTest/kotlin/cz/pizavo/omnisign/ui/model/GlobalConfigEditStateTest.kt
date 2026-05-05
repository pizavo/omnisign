package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [GlobalConfigEditState] computed properties and mapping.
 */
class GlobalConfigEditStateTest : FunSpec({

	test("effectiveSignatureLevel returns B-B when neither checkbox is checked") {
		val state = GlobalConfigEditState(addSignatureTimestamp = false, addArchivalTimestamp = false)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
	}

	test("effectiveSignatureLevel returns B-LT when only signature timestamp is checked") {
		val state = GlobalConfigEditState(addSignatureTimestamp = true, addArchivalTimestamp = false)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("effectiveSignatureLevel returns B-LTA when both checkboxes are checked") {
		val state = GlobalConfigEditState(addSignatureTimestamp = true, addArchivalTimestamp = true)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("toGlobalConfig maps effectiveSignatureLevel to defaultSignatureLevel") {
		val state = GlobalConfigEditState(addSignatureTimestamp = true, addArchivalTimestamp = true)
		state.toGlobalConfig().defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("from derives addSignatureTimestamp from B-LT level") {
		val config = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LT)
		val state = GlobalConfigEditState.from(config)
		state.addSignatureTimestamp shouldBe true
		state.addArchivalTimestamp shouldBe false
	}

	test("from derives both timestamps from B-LTA level") {
		val config = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA)
		val state = GlobalConfigEditState.from(config)
		state.addSignatureTimestamp shouldBe true
		state.addArchivalTimestamp shouldBe true
	}

	test("from derives neither timestamp from B-B level") {
		val config = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B)
		val state = GlobalConfigEditState.from(config)
		state.addSignatureTimestamp shouldBe false
		state.addArchivalTimestamp shouldBe false
	}

	test("contentEquals detects change in addSignatureTimestamp") {
		val a = GlobalConfigEditState(addSignatureTimestamp = false)
		val b = GlobalConfigEditState(addSignatureTimestamp = true)
		a.contentEquals(b) shouldBe false
	}

	test("contentEquals ignores transient fields") {
		val a = GlobalConfigEditState(saving = false, error = null)
		val b = GlobalConfigEditState(saving = true, error = "fail")
		a.contentEquals(b) shouldBe true
	}

	test("round-trip from and toGlobalConfig preserves B-LT level") {
		val original = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LT)
		val state = GlobalConfigEditState.from(original)
		state.toGlobalConfig().defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("contentEquals detects change in useNativeTitleBar") {
		val a = GlobalConfigEditState(useNativeTitleBar = false)
		val b = GlobalConfigEditState(useNativeTitleBar = true)
		a.contentEquals(b) shouldBe false
	}

	test("contentEquals ignores showNativeTitleBarOption as transient") {
		val a = GlobalConfigEditState(showNativeTitleBarOption = false)
		val b = GlobalConfigEditState(showNativeTitleBarOption = true)
		a.contentEquals(b) shouldBe true
	}

	test("contentEquals returns true when useNativeTitleBar matches") {
		val a = GlobalConfigEditState(useNativeTitleBar = true)
		val b = GlobalConfigEditState(useNativeTitleBar = true)
		a.contentEquals(b) shouldBe true
	}

	test("default useNativeTitleBar is false") {
		GlobalConfigEditState().useNativeTitleBar shouldBe false
	}

	test("default showNativeTitleBarOption is false") {
		GlobalConfigEditState().showNativeTitleBarOption shouldBe false
	}
})

