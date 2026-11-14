package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.model.value.Sensitive
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Unit tests for [GlobalConfigEditState] computed properties and mapping, including what a save
 * keeps of the configuration it is applied to and when it stamps the policy update date.
 */
class GlobalConfigEditStateTest : FunSpec({

	val today = LocalDate(2026, 11, 14)

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

	test("appliedTo maps effectiveSignatureLevel to defaultSignatureLevel") {
		val state = GlobalConfigEditState(addSignatureTimestamp = true, addArchivalTimestamp = true)
		state.appliedTo(GlobalConfig(), today).defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
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
		val b = GlobalConfigEditState(saving = true, error = SettingsError.Domain(LocalizableText.Literal("fail")))
		a.contentEquals(b) shouldBe true
	}

	test("round-trip from and appliedTo preserves B-LT level") {
		val original = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LT)
		val state = GlobalConfigEditState.from(original)
		state.appliedTo(original, today).defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
	}

	test("round-trip from and appliedTo keeps a B-T level, which the checkboxes show as neither ticked") {
		val original = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T)
		val state = GlobalConfigEditState.from(original)

		state.addSignatureTimestamp shouldBe false
		state.addArchivalTimestamp shouldBe false
		state.appliedTo(original, today).defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_T
	}

	test("appliedTo saves the level the checkboxes show once they are changed") {
		val bt = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T)
		val lt = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LT)
		val lta = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA)

		GlobalConfigEditState.from(bt).copy(addSignatureTimestamp = true).appliedTo(bt, today)
			.defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
		GlobalConfigEditState.from(lt).copy(addSignatureTimestamp = false).appliedTo(lt, today)
			.defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
		GlobalConfigEditState.from(lta).copy(addArchivalTimestamp = false).appliedTo(lta, today)
			.defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LT
		GlobalConfigEditState.from(lta).copy(addSignatureTimestamp = false, addArchivalTimestamp = false)
			.appliedTo(lta, today).defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
	}

	test("appliedTo keeps the credential key the configuration names while no new password is entered, as the CLI does") {
		val base = GlobalConfig(
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "b", credentialKey = "a"),
		)
		val state = GlobalConfigEditState.from(base, hasStoredPassword = true)

		state.appliedTo(base, today).timestampServer?.credentialKey shouldBe "a"
		state.copy(timestampUsername = "c").appliedTo(base, today).timestampServer?.credentialKey shouldBe "a"
	}

	test("appliedTo names the username as the credential key when a new password is entered") {
		val base = GlobalConfig(
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "b", credentialKey = "a"),
		)

		GlobalConfigEditState.from(base, hasStoredPassword = true)
			.copy(timestampUsername = "c", timestampPassword = Sensitive("secret"))
			.appliedTo(base, today).timestampServer?.credentialKey shouldBe "c"
	}

	test("appliedTo falls back to the username as the credential key when the configuration names none") {
		val state = GlobalConfigEditState(
			timestampEnabled = true,
			timestampUrl = "https://tsa.example.com",
			timestampUsername = "u",
			hasStoredPassword = true,
		)

		state.appliedTo(GlobalConfig(), today).timestampServer?.credentialKey shouldBe "u"
	}

	test("appliedTo names no credential key without a username, or without a password stored or entered") {
		val base = GlobalConfig(
			timestampServer = TimestampServerConfig(url = "https://tsa.example.com", username = "u", credentialKey = "u"),
		)

		GlobalConfigEditState.from(base, hasStoredPassword = true).copy(timestampUsername = "")
			.appliedTo(base, today).timestampServer?.credentialKey.shouldBeNull()
		GlobalConfigEditState.from(base, hasStoredPassword = false)
			.appliedTo(base, today).timestampServer?.credentialKey.shouldBeNull()
	}

	test("storesTimestampPassword holds only for a password with a username, for a timestamp server the save writes") {
		val entered = GlobalConfigEditState(
			timestampEnabled = true,
			timestampUrl = "https://tsa.example.com",
			timestampUsername = "u",
			timestampPassword = Sensitive("secret"),
		)

		entered.storesTimestampPassword shouldBe true
		entered.copy(timestampPassword = Sensitive("")).storesTimestampPassword shouldBe false
		entered.copy(timestampUsername = " ").storesTimestampPassword shouldBe false
		entered.copy(timestampEnabled = false).storesTimestampPassword shouldBe false
		entered.copy(timestampUrl = " ").storesTimestampPassword shouldBe false
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

	test("round-trip from and appliedTo preserves trustedListRefreshIntervalHours") {
		val original = GlobalConfig(trustedListRefreshIntervalHours = 48)
		val state = GlobalConfigEditState.from(original)
		state.trustedListRefreshInterval shouldBe "48"
		state.appliedTo(original, today).trustedListRefreshIntervalHours shouldBe 48L
	}

	test("from shows the built-in validation defaults for settings the global configuration leaves unset") {
		val state = GlobalConfigEditState.from(GlobalConfig(validation = ValidationConfig()))

		state.validationPolicyType shouldBe ValidationPolicyType.DEFAULT_ETSI
		state.checkRevocation shouldBe true
		state.useEuLotl shouldBe true
	}

	test("round-trip from and appliedTo preserves validation settings that differ from the defaults") {
		val original = GlobalConfig(
			validation = ValidationConfig(
				policyType = ValidationPolicyType.CUSTOM_FILE,
				checkRevocation = false,
				useEuLotl = false,
			),
		)

		val validation = GlobalConfigEditState.from(original).appliedTo(original, today).validation

		validation.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
		validation.checkRevocation shouldBe false
		validation.useEuLotl shouldBe false
	}

	test("appliedTo clamps a sub-hour trusted-list interval up to 1") {
		val state = GlobalConfigEditState(trustedListRefreshInterval = "0")
		state.appliedTo(GlobalConfig(), today).trustedListRefreshIntervalHours shouldBe 1L
	}

	test("appliedTo falls back to 4h when the interval string is blank") {
		val state = GlobalConfigEditState(trustedListRefreshInterval = "")
		state.appliedTo(GlobalConfig(), today).trustedListRefreshIntervalHours shouldBe 4L
	}

	test("round-trip from and appliedTo preserves pkcs11ProbeTimeoutSeconds") {
		val original = GlobalConfig(pkcs11ProbeTimeoutSeconds = 60)
		val state = GlobalConfigEditState.from(original)
		state.pkcs11ProbeTimeout shouldBe "60"
		state.appliedTo(original, today).pkcs11ProbeTimeoutSeconds shouldBe 60L
	}

	test("appliedTo clamps the probe timeout into the 1..120 range") {
		GlobalConfigEditState(pkcs11ProbeTimeout = "0").appliedTo(GlobalConfig(), today).pkcs11ProbeTimeoutSeconds shouldBe 1L
		GlobalConfigEditState(pkcs11ProbeTimeout = "999").appliedTo(GlobalConfig(), today).pkcs11ProbeTimeoutSeconds shouldBe 120L
	}

	test("appliedTo falls back to 30s when the probe timeout string is blank") {
		val state = GlobalConfigEditState(pkcs11ProbeTimeout = "")
		state.appliedTo(GlobalConfig(), today).pkcs11ProbeTimeoutSeconds shouldBe 30L
	}

	test("contentEquals detects change in trustedListRefreshInterval") {
		val a = GlobalConfigEditState(trustedListRefreshInterval = "24")
		val b = GlobalConfigEditState(trustedListRefreshInterval = "12")
		a.contentEquals(b) shouldBe false
	}

	test("isTrustedListRefreshIntervalValid accepts blank and 1 to Int.MAX_VALUE hours, rejects zero and more") {
		GlobalConfigEditState(trustedListRefreshInterval = "").isTrustedListRefreshIntervalValid shouldBe true
		GlobalConfigEditState(trustedListRefreshInterval = "1").isTrustedListRefreshIntervalValid shouldBe true
		GlobalConfigEditState(trustedListRefreshInterval = Int.MAX_VALUE.toString())
			.isTrustedListRefreshIntervalValid shouldBe true
		GlobalConfigEditState(trustedListRefreshInterval = "0").isTrustedListRefreshIntervalValid shouldBe false
		GlobalConfigEditState(trustedListRefreshInterval = "2147483648").isTrustedListRefreshIntervalValid shouldBe false
		GlobalConfigEditState(trustedListRefreshInterval = "3000000000000").isTrustedListRefreshIntervalValid shouldBe false
	}

	test("from populates the trusted certificate baseline") {
		val cert = TrustedCertificate(
			fingerprint = "sha256-aa",
			subjectDN = "CN=ca",
			notBefore = Instant.parse("2024-01-01T00:00:00Z"),
			notAfter = Instant.parse("2030-01-01T00:00:00Z"),
			type = TrustedCertificateType.CA,
		)
		val state = GlobalConfigEditState.from(GlobalConfig(), trustedCertificates = listOf(cert))
		state.trustedCertificates shouldHaveSize 1
	}

	test("contentEquals detects a staged certificate addition") {
		val a = GlobalConfigEditState()
		val b = GlobalConfigEditState(
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
		val a = GlobalConfigEditState()
		val b = GlobalConfigEditState(pendingTrustedCertRemovals = setOf("sha256-aa"))
		a.contentEquals(b) shouldBe false
	}

	test("contentEquals ignores the trusted certificate baseline") {
		val cert = TrustedCertificate(
			fingerprint = "sha256-aa",
			subjectDN = "CN=ca",
			notBefore = Instant.parse("2024-01-01T00:00:00Z"),
			notAfter = Instant.parse("2030-01-01T00:00:00Z"),
			type = TrustedCertificateType.CA,
		)
		val a = GlobalConfigEditState()
		val b = GlobalConfigEditState(trustedCertificates = listOf(cert))
		a.contentEquals(b) shouldBe true
	}

	test("appliedTo keeps the expiration date overrides, the policy update date and the trusted certificate references the dialog does not show") {
		val base = GlobalConfig(
			validation = ValidationConfig(
				trustedCertificates = listOf(TrustedCertificateRef(path = "certs/root.pem", type = TrustedCertificateType.CA)),
				algorithmConstraints = AlgorithmConstraintsConfig(
					expirationLevel = AlgorithmConstraintLevel.WARN,
					policyUpdateDate = "2026-11-12",
					expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01", "RSA:1900" to "2030-12-31"),
				),
			),
		)

		val validation = GlobalConfigEditState.from(base).copy(checkRevocation = false).appliedTo(base, today).validation

		validation.checkRevocation shouldBe false
		validation.trustedCertificates shouldBe base.validation.trustedCertificates
		validation.algorithmConstraints shouldBe base.validation.algorithmConstraints.copy(
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN,
		)
	}

	test("appliedTo stamps the policy update date with today when the expiration level changes") {
		val base = GlobalConfig(
			validation = ValidationConfig(
				algorithmConstraints = AlgorithmConstraintsConfig(
					policyUpdateDate = "2026-02-01",
					expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01"),
				),
			),
		)

		val constraints = GlobalConfigEditState.from(base)
			.copy(algoExpirationLevel = AlgorithmConstraintLevel.WARN)
			.appliedTo(base, today).validation.algorithmConstraints

		constraints.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
		constraints.policyUpdateDate shouldBe "2026-11-14"
		constraints.expirationDateOverrides shouldBe mapOf("RIPEMD160" to "2030-01-01")
	}

	test("appliedTo stamps the policy update date with today when the after-update level changes") {
		val base = GlobalConfig()

		val constraints = GlobalConfigEditState.from(base)
			.copy(algoExpirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM)
			.appliedTo(base, today).validation.algorithmConstraints

		constraints.expirationLevelAfterUpdate shouldBe AlgorithmConstraintLevel.INFORM
		constraints.policyUpdateDate shouldBe "2026-11-14"
	}

	test("appliedTo leaves the policy update date alone when the levels are the ones the configuration resolves to") {
		val unset = GlobalConfig()
		val explicit = GlobalConfig(
			validation = ValidationConfig(
				algorithmConstraints = AlgorithmConstraintsConfig(
					expirationLevel = AlgorithmConstraintLevel.INFORM,
					expirationLevelAfterUpdate = AlgorithmConstraintLevel.FAIL,
					policyUpdateDate = "2026-11-12",
				),
			),
		)

		GlobalConfigEditState.from(unset).appliedTo(unset, today)
			.validation.algorithmConstraints.policyUpdateDate.shouldBeNull()
		GlobalConfigEditState.from(explicit).appliedTo(explicit, today)
			.validation.algorithmConstraints.policyUpdateDate shouldBe "2026-11-12"
	}

	test("the default state is the one a configuration that sets nothing loads into, so it saves the same") {
		val state = GlobalConfigEditState()
		val loaded = GlobalConfigEditState.from(GlobalConfig())

		state.contentEquals(loaded) shouldBe true
		state.appliedTo(GlobalConfig(), today) shouldBe loaded.appliedTo(GlobalConfig(), today)
	}

	test("toString does not show the TSA password") {
		GlobalConfigEditState(timestampPassword = Sensitive("secret123")).toString() shouldNotContain "secret123"
	}

	test("invalidFieldError names the first field out of its range, and nothing when every field is in range") {
		val valid = GlobalConfigEditState()

		valid.invalidFieldError.shouldBeNull()
		valid.copy(schedulerHour = "24").invalidFieldError shouldBe SettingsError.SchedulerTimeInvalid
		valid.copy(pkcs11ProbeTimeout = "0").invalidFieldError shouldBe SettingsError.Pkcs11ProbeTimeoutInvalid
		valid.copy(pkcs11ProbeTimeout = "121").invalidFieldError shouldBe SettingsError.Pkcs11ProbeTimeoutInvalid
		valid.copy(trustedListRefreshInterval = "0").invalidFieldError shouldBe
				SettingsError.TrustedListRefreshIntervalInvalid
		valid.copy(stalenessNotificationEnabled = true, stalenessThresholdDays = "0").invalidFieldError shouldBe
				SettingsError.StalenessThresholdInvalid
		valid.copy(schedulerMinute = "60", pkcs11ProbeTimeout = "0").invalidFieldError shouldBe
				SettingsError.SchedulerTimeInvalid
		valid.copy(timestampEnabled = true, timestampPassword = Sensitive("secret")).invalidFieldError shouldBe
				SettingsError.TsaPasswordNeedsUsername
		valid.copy(timestampEnabled = true, timestampPassword = Sensitive("secret"), timestampUsername = "u")
			.invalidFieldError.shouldBeNull()
	}

	test("invalidFieldError ignores the staleness threshold while its warning is off") {
		GlobalConfigEditState(stalenessNotificationEnabled = false, stalenessThresholdDays = "0")
			.invalidFieldError.shouldBeNull()
	}

	test("invalidFieldError ignores a TSA password without a username while the timestamp server section is off") {
		GlobalConfigEditState(timestampEnabled = false, timestampPassword = Sensitive("secret"))
			.invalidFieldError.shouldBeNull()
	}
})

