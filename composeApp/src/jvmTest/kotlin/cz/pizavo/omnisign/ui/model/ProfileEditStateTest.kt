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
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
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

	/** Return the level a form with no loaded profile and the toggles [signature] and [archival] saves under [global]. */
	fun levelOf(signature: TriToggleState, archival: TriToggleState, global: SignatureLevel): SignatureLevel? =
		ProfileEditState(profileName = "p", signatureTimestampOverride = signature, archivalTimestampOverride = archival)
			.effectiveSignatureLevel(global)

	/** Return whether a form with no loaded profile and the given toggles shows the archival timestamp active under [global]. */
	fun archivalActive(archival: TriToggleState, global: SignatureLevel, signature: TriToggleState = TriToggleState.INHERIT) =
		ProfileEditState(profileName = "p", signatureTimestampOverride = signature, archivalTimestampOverride = archival)
			.archivalTimestampActive(global)

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

	test("a profile passed as the base supplies the settings the form does not show, in place of the loaded one") {
		val loaded = ProfileConfig(
			name = "p",
			ocsp = OcspConfig(timeout = 5000),
			validation = ValidationConfig(policyType = ValidationPolicyType.CUSTOM_FILE, customPolicyPath = "policy.xml"),
		)
		val stored = ProfileConfig(name = "p", crl = CrlConfig(timeout = 7000))

		val saved = ProfileEditState.from(loaded).copy(description = "new")
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B, base = stored)

		saved shouldBe stored.copy(description = "new")
	}

	test("the archival timestamp is active when the profile turns it on or inherits a global B-LTA") {
		archivalActive(TriToggleState.ENABLED, SignatureLevel.PADES_BASELINE_B) shouldBe true
		archivalActive(TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_LTA) shouldBe true
		archivalActive(TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_LTA, signature = TriToggleState.DISABLED) shouldBe true
		archivalActive(TriToggleState.INHERIT, SignatureLevel.PADES_BASELINE_LT) shouldBe false
		archivalActive(TriToggleState.DISABLED, SignatureLevel.PADES_BASELINE_LTA) shouldBe false
	}

	test("the archival timestamp shows as active exactly when the level the profile saves, or inherits, is B-LTA") {
		val loadedLevels = listOf(null) + SignatureLevel.entries
		val forms = loadedLevels.map { ProfileEditState.from(ProfileConfig(name = "p", signatureLevel = it)) } +
			ProfileEditState(profileName = "p")
		for (form in forms) for (global in SignatureLevel.entries) {
			for (signature in TriToggleState.entries) for (archival in TriToggleState.entries) {
				val state = form.copy(signatureTimestampOverride = signature, archivalTimestampOverride = archival)
				val saved = state.toProfileConfig(global).signatureLevel ?: global
				withClue("loaded ${form.loadedProfile?.signatureLevel}, global $global, toggles $signature/$archival") {
					state.archivalTimestampActive(global) shouldBe (saved == SignatureLevel.PADES_BASELINE_LTA)
				}
			}
		}
	}

	test("a kept TSA password keeps the credential key it is stored under, also when the username changes") {
		val profile = ProfileConfig(
			name = "p",
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "alice", credentialKey = "alice-key"),
		)

		val saved = ProfileEditState.from(profile, hasStoredPassword = true).copy(timestampUsername = "bob")
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B)

		saved.timestampServer.shouldNotBeNull().credentialKey shouldBe "alice-key"
	}

	test("a kept TSA password keeps the credential key the form was loaded with, whatever key the profile stored at the save names") {
		val loaded = ProfileConfig(
			name = "p",
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "alice", credentialKey = "alice-key"),
		)
		val stored = loaded.copy(
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "bob", credentialKey = "bob-key"),
		)

		val tsa = ProfileEditState.from(loaded, hasStoredPassword = true)
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B, base = stored)
			.timestampServer.shouldNotBeNull()

		tsa.username shouldBe "alice"
		tsa.credentialKey shouldBe "alice-key"
	}

	test("a new TSA password is stored under the username, and a kept one the profile names no key for is looked up by it") {
		val profile = ProfileConfig(
			name = "p",
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "alice", credentialKey = "alice"),
		)

		ProfileEditState.from(profile, hasStoredPassword = true)
			.copy(timestampUsername = "bob", timestampPassword = Sensitive("new"))
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B)
			.timestampServer.shouldNotBeNull().credentialKey shouldBe "bob"
		ProfileEditState(
			profileName = "p",
			timestampEnabled = true,
			timestampUrl = "https://tsa.example.com",
			timestampUsername = "bob",
			hasStoredPassword = true,
		).toProfileConfig(SignatureLevel.PADES_BASELINE_B).timestampServer.shouldNotBeNull().credentialKey shouldBe "bob"
	}

	test("a TSA section without a password, or without a username, names no credential key") {
		val profile = ProfileConfig(
			name = "p",
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "alice", credentialKey = "alice"),
		)

		ProfileEditState.from(profile, hasStoredPassword = false).toProfileConfig(SignatureLevel.PADES_BASELINE_B)
			.timestampServer.shouldNotBeNull().credentialKey.shouldBeNull()
		ProfileEditState.from(profile, hasStoredPassword = true).copy(timestampUsername = " ")
			.toProfileConfig(SignatureLevel.PADES_BASELINE_B)
			.timestampServer.shouldNotBeNull().credentialKey.shouldBeNull()
	}

	test("toString hides the TSA password") {
		val state = ProfileEditState(profileName = "p", timestampPassword = Sensitive("s3cret"))

		state.toString() shouldNotContain "s3cret"
	}
})
