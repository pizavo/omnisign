package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Unit tests for computed properties on [ValidationReport].
 */
class ValidationReportTest : FunSpec({

	val baseCert = CertificateInfo(
		subjectDN = "CN=Test",
		issuerDN = "CN=CA",
		serialNumber = "1",
		validFrom = Instant.parse("2025-01-01T00:00:00Z"),
		validTo = Instant.parse("2027-01-01T00:00:00Z"),
	)

	fun sig(
		indication: ValidationIndication = ValidationIndication.TOTAL_PASSED,
		tier: SignatureTrustTier = SignatureTrustTier.NOT_QUALIFIED,
		euLotlBacked: Boolean = false,
	) = SignatureValidationResult(
		signatureId = "s",
		indication = indication,
		signedBy = "Tester",
		signatureLevel = "PAdES-BASELINE-B",
		signatureTime = Instant.parse("2026-01-01T00:00:00Z"),
		certificate = baseCert,
		trustTier = tier,
		euLotlBacked = euLotlBacked,
	)

	fun report(
		overall: ValidationResult = ValidationResult.VALID,
		signatures: List<SignatureValidationResult> = emptyList(),
	) = ValidationReport(
		documentName = "doc.pdf",
		validationTime = Instant.parse("2026-01-01T00:00:00Z"),
		overallResult = overall,
		signatures = signatures,
	)

	test("overallTrustTier returns NOT_QUALIFIED when there are no signatures") {
		report().overallTrustTier shouldBe SignatureTrustTier.NOT_QUALIFIED
	}

	test("overallTrustTier returns NOT_QUALIFIED when overall result is INVALID") {
		report(
			overall = ValidationResult.INVALID,
			signatures = listOf(
				sig(
					indication = ValidationIndication.TOTAL_FAILED,
					tier = SignatureTrustTier.QUALIFIED_QSCD,
				)
			),
		).overallTrustTier shouldBe SignatureTrustTier.NOT_QUALIFIED
	}

	test("overallTrustTier returns NOT_QUALIFIED when overall result is INDETERMINATE") {
		report(
			overall = ValidationResult.INDETERMINATE,
			signatures = listOf(
				sig(
					indication = ValidationIndication.INDETERMINATE,
					tier = SignatureTrustTier.QUALIFIED,
				)
			),
		).overallTrustTier shouldBe SignatureTrustTier.NOT_QUALIFIED
	}

	test("overallTrustTier returns QUALIFIED_QSCD when a passed signature has QUALIFIED_QSCD") {
		report(
			signatures = listOf(
				sig(tier = SignatureTrustTier.QUALIFIED_QSCD),
			),
		).overallTrustTier shouldBe SignatureTrustTier.QUALIFIED_QSCD
	}

	test("overallTrustTier returns QUALIFIED when a passed signature has QUALIFIED") {
		report(
			signatures = listOf(
				sig(tier = SignatureTrustTier.QUALIFIED),
			),
		).overallTrustTier shouldBe SignatureTrustTier.QUALIFIED
	}

	test("overallTrustTier picks the highest tier among multiple passed signatures") {
		report(
			signatures = listOf(
				sig(tier = SignatureTrustTier.QUALIFIED),
				sig(tier = SignatureTrustTier.QUALIFIED_QSCD),
				sig(tier = SignatureTrustTier.NOT_QUALIFIED),
			),
		).overallTrustTier shouldBe SignatureTrustTier.QUALIFIED_QSCD
	}

	test("overallTrustTier ignores failed signatures even if they are qualified") {
		report(
			overall = ValidationResult.INDETERMINATE,
			signatures = listOf(
				sig(
					indication = ValidationIndication.TOTAL_FAILED,
					tier = SignatureTrustTier.QUALIFIED_QSCD,
				),
				sig(
					indication = ValidationIndication.INDETERMINATE,
					tier = SignatureTrustTier.NOT_QUALIFIED,
				),
			),
		).overallTrustTier shouldBe SignatureTrustTier.NOT_QUALIFIED
	}

	test("overallTrustTier returns NOT_QUALIFIED when all passed signatures are NOT_QUALIFIED") {
		report(
			signatures = listOf(
				sig(tier = SignatureTrustTier.NOT_QUALIFIED),
				sig(tier = SignatureTrustTier.NOT_QUALIFIED),
			),
		).overallTrustTier shouldBe SignatureTrustTier.NOT_QUALIFIED
	}

	test("overallEuLotlBacked is false when there are no signatures") {
		report().overallEuLotlBacked shouldBe false
	}

	test("overallEuLotlBacked is true when every signature's cert is on the LOTL") {
		report(
			signatures = listOf(
				sig(euLotlBacked = true),
				sig(euLotlBacked = true),
			),
		).overallEuLotlBacked shouldBe true
	}

	test("overallEuLotlBacked is false when any signature's cert is not on the LOTL") {
		report(
			signatures = listOf(
				sig(euLotlBacked = true),
				sig(euLotlBacked = false),
			),
		).overallEuLotlBacked shouldBe false
	}

	test("overallEuLotlBacked counts a failed signature's cert: false when it is off the LOTL") {
		report(
			overall = ValidationResult.INVALID,
			signatures = listOf(
				sig(euLotlBacked = true),
				sig(indication = ValidationIndication.TOTAL_FAILED, euLotlBacked = false),
			),
		).overallEuLotlBacked shouldBe false
	}

	test("overallEuLotlBacked is independent of overall validity: true when all certs on the LOTL despite a failed signature") {
		report(
			overall = ValidationResult.INVALID,
			signatures = listOf(
				sig(euLotlBacked = true),
				sig(indication = ValidationIndication.TOTAL_FAILED, euLotlBacked = true),
			),
		).overallEuLotlBacked shouldBe true
	}
})

