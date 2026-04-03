package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.lumo.components.TriToggleState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [ProfileEditState] computed properties and TriToggleState mapping.
 */
class ProfileEditStateTest : FunSpec({

	test("effectiveSignatureLevel returns null when both toggles are INHERIT") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.INHERIT,
			archivalTimestampOverride = TriToggleState.INHERIT,
		)
		state.effectiveSignatureLevel.shouldBeNull()
	}

	test("effectiveSignatureLevel returns B-B when both toggles are DISABLED") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.DISABLED,
			archivalTimestampOverride = TriToggleState.DISABLED,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
	}

	test("effectiveSignatureLevel returns B-LT when signature is ENABLED, archival is DISABLED") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.ENABLED,
			archivalTimestampOverride = TriToggleState.DISABLED,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("effectiveSignatureLevel returns B-LTA when both toggles are ENABLED") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.ENABLED,
			archivalTimestampOverride = TriToggleState.ENABLED,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("effectiveSignatureLevel returns B-LTA when archival is ENABLED and signature is INHERIT") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.INHERIT,
			archivalTimestampOverride = TriToggleState.ENABLED,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("effectiveSignatureLevel returns B-B when signature is DISABLED and archival is INHERIT") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.DISABLED,
			archivalTimestampOverride = TriToggleState.INHERIT,
		)
		state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
	}

	test("from maps null signatureLevel to both INHERIT") {
		val profile = ProfileConfig(name = "p", signatureLevel = null)
		val state = ProfileEditState.from(profile)
		state.signatureTimestampOverride shouldBe TriToggleState.INHERIT
		state.archivalTimestampOverride shouldBe TriToggleState.INHERIT
	}

	test("from maps B-B to both DISABLED") {
		val profile = ProfileConfig(name = "p", signatureLevel = SignatureLevel.PADES_BASELINE_B)
		val state = ProfileEditState.from(profile)
		state.signatureTimestampOverride shouldBe TriToggleState.DISABLED
		state.archivalTimestampOverride shouldBe TriToggleState.DISABLED
	}

	test("from maps B-LT to signature ENABLED, archival DISABLED") {
		val profile = ProfileConfig(name = "p", signatureLevel = SignatureLevel.PADES_BASELINE_LT)
		val state = ProfileEditState.from(profile)
		state.signatureTimestampOverride shouldBe TriToggleState.ENABLED
		state.archivalTimestampOverride shouldBe TriToggleState.DISABLED
	}

	test("from maps B-LTA to both ENABLED") {
		val profile = ProfileConfig(name = "p", signatureLevel = SignatureLevel.PADES_BASELINE_LTA)
		val state = ProfileEditState.from(profile)
		state.signatureTimestampOverride shouldBe TriToggleState.ENABLED
		state.archivalTimestampOverride shouldBe TriToggleState.ENABLED
	}

	test("toProfileConfig maps toggle states back to signatureLevel") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.ENABLED,
			archivalTimestampOverride = TriToggleState.ENABLED,
		)
		state.toProfileConfig().signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("toProfileConfig maps INHERIT toggles to null signatureLevel") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.INHERIT,
			archivalTimestampOverride = TriToggleState.INHERIT,
		)
		state.toProfileConfig().signatureLevel.shouldBeNull()
	}

	test("round-trip from and toProfileConfig preserves B-LT level") {
		val original = ProfileConfig(name = "p", signatureLevel = SignatureLevel.PADES_BASELINE_LT)
		val state = ProfileEditState.from(original)
		state.toProfileConfig().signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("round-trip from and toProfileConfig preserves null level") {
		val original = ProfileConfig(name = "p", signatureLevel = null)
		val state = ProfileEditState.from(original)
		state.toProfileConfig().signatureLevel.shouldBeNull()
	}

	test("contentEquals detects change in signatureTimestampOverride") {
		val a = ProfileEditState(profileName = "p", signatureTimestampOverride = TriToggleState.INHERIT)
		val b = ProfileEditState(profileName = "p", signatureTimestampOverride = TriToggleState.ENABLED)
		a.contentEquals(b) shouldBe false
	}

	test("contentEquals ignores transient fields") {
		val a = ProfileEditState(profileName = "p", saving = false, error = null)
		val b = ProfileEditState(profileName = "p", saving = true, error = "fail")
		a.contentEquals(b) shouldBe true
	}
})

