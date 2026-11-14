package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.lumo.components.TriToggleState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Instant

/**
 * Unit tests for [ProfileEditState] computed properties, TriToggleState mapping, and how the
 * profile it saves resolves against the global configuration.
 */
class ProfileEditStateTest : FunSpec({

	fun levelOf(signature: TriToggleState, archival: TriToggleState, global: SignatureLevel): SignatureLevel? =
		ProfileEditState(profileName = "p", signatureTimestampOverride = signature, archivalTimestampOverride = archival)
			.effectiveSignatureLevel(global)

	test("effectiveSignatureLevel returns null when both toggles are INHERIT, whatever the global level") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.INHERIT,
			archivalTimestampOverride = TriToggleState.INHERIT,
		)
		for (global in SignatureLevel.entries) {
			withClue(global) { state.effectiveSignatureLevel(global).shouldBeNull() }
		}
	}

	test("effectiveSignatureLevel returns B-B when both toggles are DISABLED, whatever the global level") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.DISABLED,
			archivalTimestampOverride = TriToggleState.DISABLED,
		)
		for (global in SignatureLevel.entries) {
			withClue(global) { state.effectiveSignatureLevel(global) shouldBe SignatureLevel.PADES_BASELINE_B }
		}
	}

	test("effectiveSignatureLevel returns B-LT when signature is ENABLED, archival is DISABLED, whatever the global level") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.ENABLED,
			archivalTimestampOverride = TriToggleState.DISABLED,
		)
		for (global in SignatureLevel.entries) {
			withClue(global) { state.effectiveSignatureLevel(global) shouldBe SignatureLevel.PADES_BASELINE_LT }
		}
	}

	test("effectiveSignatureLevel returns B-LTA when both toggles are ENABLED, whatever the global level") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.ENABLED,
			archivalTimestampOverride = TriToggleState.ENABLED,
		)
		for (global in SignatureLevel.entries) {
			withClue(global) { state.effectiveSignatureLevel(global) shouldBe SignatureLevel.PADES_BASELINE_LTA }
		}
	}

	test("effectiveSignatureLevel returns B-LTA when archival is ENABLED and signature is INHERIT, whatever the global level") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.INHERIT,
			archivalTimestampOverride = TriToggleState.ENABLED,
		)
		for (global in SignatureLevel.entries) {
			withClue(global) { state.effectiveSignatureLevel(global) shouldBe SignatureLevel.PADES_BASELINE_LTA }
		}
	}

	test("an archival toggle left on INHERIT is on only when the global level is B-LTA") {
		levelOf(TriToggleState.DISABLED, TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_LTA) shouldBe SignatureLevel.PADES_BASELINE_LTA
		levelOf(TriToggleState.ENABLED, TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_LTA) shouldBe SignatureLevel.PADES_BASELINE_LTA
		levelOf(TriToggleState.DISABLED, TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_LT) shouldBe SignatureLevel.PADES_BASELINE_B
		levelOf(TriToggleState.ENABLED, TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_LT) shouldBe SignatureLevel.PADES_BASELINE_LT
		levelOf(TriToggleState.ENABLED, TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_B) shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("a signature toggle left on INHERIT takes the global level's signature timestamp") {
		levelOf(TriToggleState.INHERIT, TriToggleState.DISABLED, SignatureLevel.PADES_BASELINE_B) shouldBe SignatureLevel.PADES_BASELINE_B
		levelOf(TriToggleState.INHERIT, TriToggleState.DISABLED, SignatureLevel.PADES_BASELINE_T) shouldBe SignatureLevel.PADES_BASELINE_T
		levelOf(TriToggleState.INHERIT, TriToggleState.DISABLED, SignatureLevel.PADES_BASELINE_LT) shouldBe SignatureLevel.PADES_BASELINE_LT
		levelOf(TriToggleState.INHERIT, TriToggleState.DISABLED, SignatureLevel.PADES_BASELINE_LTA) shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("toProfileConfig saves a one-sided INHERIT resolved against the global level") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.ENABLED,
			archivalTimestampOverride = TriToggleState.INHERIT,
		)
		state.toProfileConfig(SignatureLevel.PADES_BASELINE_LTA).signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("a loaded B-T level is saved as B-T until a timestamp toggle changes") {
		val state = ProfileEditState.from(ProfileConfig(name = "p", signatureLevel = SignatureLevel.PADES_BASELINE_T))

		state.toProfileConfig(SignatureLevel.PADES_BASELINE_B).signatureLevel shouldBe SignatureLevel.PADES_BASELINE_T
		state.copy(archivalTimestampOverride = TriToggleState.ENABLED)
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B).signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
		state.copy(signatureTimestampOverride = TriToggleState.DISABLED)
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B).signatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
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
		state.toProfileConfig(SignatureLevel.PADES_BASELINE_B).signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("toProfileConfig maps INHERIT toggles to null signatureLevel") {
		val state = ProfileEditState(
			profileName = "p",
			signatureTimestampOverride = TriToggleState.INHERIT,
			archivalTimestampOverride = TriToggleState.INHERIT,
		)
		state.toProfileConfig(SignatureLevel.PADES_BASELINE_B).signatureLevel.shouldBeNull()
	}

	test("round-trip from and toProfileConfig preserves B-LT level") {
		val original = ProfileConfig(name = "p", signatureLevel = SignatureLevel.PADES_BASELINE_LT)
		val state = ProfileEditState.from(original)
		state.toProfileConfig(SignatureLevel.PADES_BASELINE_B).signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("round-trip from and toProfileConfig preserves null level") {
		val original = ProfileConfig(name = "p", signatureLevel = null)
		val state = ProfileEditState.from(original)
		state.toProfileConfig(SignatureLevel.PADES_BASELINE_B).signatureLevel.shouldBeNull()
	}

	test("a profile saved with its own trusted list keeps the global policy, revocation and EU LOTL settings") {
		val state = ProfileEditState(
			profileName = "internal",
			customTrustedLists = listOf(CustomTrustedListConfig(name = "corp", source = "https://example.com/corp.xml")),
		)
		val global = GlobalConfig(
			validation = ValidationConfig(
				policyType = ValidationPolicyType.CUSTOM_FILE,
				checkRevocation = false,
				useEuLotl = false,
			),
		)

		val resolved = ResolvedConfig.resolve(global, state.toProfileConfig(global.defaultSignatureLevel), operationOverrides = null)
			.getOrNull().shouldNotBeNull().validation

		resolved.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
		resolved.checkRevocation shouldBe false
		resolved.useEuLotl shouldBe false
		resolved.customTrustedLists.map { it.name } shouldBe listOf("corp")
	}

	test("contentEquals detects change in signatureTimestampOverride") {
		val a = ProfileEditState(profileName = "p", signatureTimestampOverride = TriToggleState.INHERIT)
		val b = ProfileEditState(profileName = "p", signatureTimestampOverride = TriToggleState.ENABLED)
		a.contentEquals(b) shouldBe false
	}

	test("contentEquals ignores transient fields") {
		val a = ProfileEditState(profileName = "p", saving = false, error = null)
		val b = ProfileEditState(profileName = "p", saving = true, error = ProfileError.NameRequired)
		a.contentEquals(b) shouldBe true
	}

	test("from populates the trusted certificate baseline") {
		val cert = TrustedCertificate(
			fingerprint = "sha256-aa",
			subjectDN = "CN=ca",
			notBefore = Instant.parse("2024-01-01T00:00:00Z"),
			notAfter = Instant.parse("2030-01-01T00:00:00Z"),
			type = TrustedCertificateType.CA,
		)
		val state = ProfileEditState.from(ProfileConfig(name = "p"), trustedCertificates = listOf(cert))
		state.trustedCertificates shouldHaveSize 1
	}

	test("contentEquals detects a staged certificate addition") {
		val a = ProfileEditState(profileName = "p")
		val b = ProfileEditState(
			profileName = "p",
			pendingTrustedCertAdds = listOf(
				PendingTrustedCert(
					source = "ca.pem",
					type = TrustedCertificateType.CA,
					bytes = byteArrayOf(1),
					fingerprint = "sha256-ca",
					subjectDN = "CN=ca",
					notAfter = Instant.parse("2030-01-01T00:00:00Z"),
				),
			),
		)
		a.contentEquals(b) shouldBe false
	}

	test("contentEquals detects a staged certificate removal") {
		val a = ProfileEditState(profileName = "p")
		val b = ProfileEditState(profileName = "p", pendingTrustedCertRemovals = setOf("sha256-aa"))
		a.contentEquals(b) shouldBe false
	}

	test("saving a loaded profile keeps every setting the form does not show") {
		val profile = ProfileConfig(
			name = "legacy",
			description = "old",
			signatureLevel = SignatureLevel.PADES_BASELINE_T,
			ocsp = OcspConfig(timeout = 5000),
			crl = CrlConfig(timeout = 7000),
			validation = ValidationConfig(
				policyType = ValidationPolicyType.CUSTOM_FILE,
				customPolicyPath = "policy.xml",
				checkRevocation = false,
				useEuLotl = false,
				alertIfNotEuLotl = true,
				customTrustedLists = listOf(CustomTrustedListConfig(name = "corp", source = "https://example.com/corp.xml")),
				trustedCertificates = listOf(TrustedCertificateRef(inline = "AAAA", type = TrustedCertificateType.CA)),
				algorithmConstraints = AlgorithmConstraintsConfig(
					expirationLevel = AlgorithmConstraintLevel.WARN,
					expirationLevelAfterUpdate = AlgorithmConstraintLevel.FAIL,
					policyUpdateDate = "2025-01-01",
					expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01"),
				),
				allowExpiredCertificate = false,
			),
		)

		val saved = ProfileEditState.from(profile).copy(description = "new")
			.toProfileConfig(SignatureLevel.PADES_BASELINE_LTA)

		saved shouldBe profile.copy(description = "new")
	}

	test("the form's validation settings are written into the loaded validation section") {
		val corp = CustomTrustedListConfig(name = "corp", source = "https://example.com/corp.xml")
		val profile = ProfileConfig(
			name = "p",
			validation = ValidationConfig(policyType = ValidationPolicyType.CUSTOM_FILE, alertIfNotEuLotl = true),
		)

		val saved = ProfileEditState.from(profile).copy(
			alertIfNotEuLotlOverride = TriToggleState.INHERIT,
			allowExpiredCertificateOverride = TriToggleState.ENABLED,
			customTrustedLists = listOf(corp),
		).toProfileConfig(SignatureLevel.PADES_BASELINE_B)

		saved.validation shouldBe ValidationConfig(
			policyType = ValidationPolicyType.CUSTOM_FILE,
			customTrustedLists = listOf(corp),
			allowExpiredCertificate = true,
		)
	}

	test("a validation section the form empties is left out") {
		val profile = ProfileConfig(name = "p", validation = ValidationConfig(alertIfNotEuLotl = true))

		val saved = ProfileEditState.from(profile).copy(alertIfNotEuLotlOverride = TriToggleState.INHERIT)
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B)

		saved.validation.shouldBeNull()
	}

	test("toString hides the TSA password") {
		val state = ProfileEditState(profileName = "p", timestampPassword = Sensitive("s3cret"))

		state.toString() shouldNotContain "s3cret"
	}
})
