package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

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

	test("round-trip from and toGlobalConfig preserves trustedListRefreshIntervalHours") {
		val original = GlobalConfig(trustedListRefreshIntervalHours = 48)
		val state = GlobalConfigEditState.from(original)
		state.trustedListRefreshInterval shouldBe "48"
		state.toGlobalConfig().trustedListRefreshIntervalHours shouldBe 48L
	}

	test("toGlobalConfig clamps a sub-hour trusted-list interval up to 1") {
		val state = GlobalConfigEditState(trustedListRefreshInterval = "0")
		state.toGlobalConfig().trustedListRefreshIntervalHours shouldBe 1L
	}

	test("toGlobalConfig falls back to 24h when the interval string is blank") {
		val state = GlobalConfigEditState(trustedListRefreshInterval = "")
		state.toGlobalConfig().trustedListRefreshIntervalHours shouldBe 24L
	}

	test("contentEquals detects change in trustedListRefreshInterval") {
		val a = GlobalConfigEditState(trustedListRefreshInterval = "24")
		val b = GlobalConfigEditState(trustedListRefreshInterval = "12")
		a.contentEquals(b) shouldBe false
	}

	test("isTrustedListRefreshIntervalValid accepts blank and positive, rejects zero") {
		GlobalConfigEditState(trustedListRefreshInterval = "").isTrustedListRefreshIntervalValid shouldBe true
		GlobalConfigEditState(trustedListRefreshInterval = "1").isTrustedListRefreshIntervalValid shouldBe true
		GlobalConfigEditState(trustedListRefreshInterval = "0").isTrustedListRefreshIntervalValid shouldBe false
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
})

