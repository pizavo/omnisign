package cz.pizavo.omnisign.ui.screenshot

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.validation.SignatureTrustTier
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.ui.layout.SignaturePanel
import cz.pizavo.omnisign.ui.model.SignaturePanelState
import io.kotest.core.spec.style.FunSpec
import kotlin.time.Instant

/**
 * Screenshots of the [SignaturePanel], which shows the validation result beside the PDF: before validation, after
 * an error, and with a valid and an indeterminate signature opened, the valid one in both themes.
 */
@OptIn(ExperimentalTestApi::class)
class SignaturePanelScreenshotTest : FunSpec({
	val certificate = CertificateInfo(
		subjectDN = "CN=Jana Nováková, O=Example s.r.o., C=CZ",
		issuerDN = "CN=Example Qualified CA, O=Example a.s., C=CZ",
		serialNumber = "1A2B3C4D5E6F",
		validFrom = Instant.parse("2025-01-01T00:00:00Z"),
		validTo = Instant.parse("2028-01-01T00:00:00Z"),
		isQualified = true,
	)
	val validSignature = SignatureValidationResult(
		signatureId = "sig-1",
		indication = ValidationIndication.TOTAL_PASSED,
		signedBy = "Jana Nováková",
		signatureLevel = "PAdES-BASELINE-LTA",
		signatureTime = Instant.parse("2026-03-27T09:00:00Z"),
		certificate = certificate,
		signatureQualification = "QESig",
		trustTier = SignatureTrustTier.QUALIFIED_QSCD,
		euLotlBacked = true,
		hashAlgorithm = "SHA256",
		encryptionAlgorithm = "RSA",
	)
	val validReport = ValidationReport(
		documentName = "contract.pdf",
		validationTime = Instant.parse("2026-03-27T10:00:00Z"),
		overallResult = ValidationResult.VALID,
		signatures = listOf(validSignature),
	)
	val indeterminateReport = validReport.copy(
		overallResult = ValidationResult.INDETERMINATE,
		signatures = listOf(
			validSignature.copy(
				indication = ValidationIndication.INDETERMINATE,
				subIndication = "NO_CERTIFICATE_CHAIN_FOUND",
				signatureQualification = null,
				trustTier = SignatureTrustTier.NOT_QUALIFIED,
				euLotlBacked = false,
				warnings = listOf(LocalizableText.Literal("The certificate chain could not be built to a trusted anchor.")),
			),
		),
	)
	val openSignature: ComposeUiTest.() -> Unit = {
		onNodeWithText("Signature 1 of 1", substring = true).performSemanticsAction(SemanticsActions.OnClick)
	}

	test("before validation") {
		captureScreenshot("signature-panel-idle") {
			SignaturePanel(SignaturePanelState.Idle(hasDocument = true), onLoadSignatures = {})
		}
	}

	test("after a validation error") {
		captureScreenshot("signature-panel-error") {
			SignaturePanel(
				SignaturePanelState.Error(LocalizableText.Literal("The file is not a PDF document.")),
				onLoadSignatures = {},
			)
		}
	}

	test("a valid qualified signature") {
		captureScreenshot("signature-panel-valid", interaction = openSignature) {
			SignaturePanel(SignaturePanelState.Loaded(validReport), onLoadSignatures = {})
		}
	}

	test("a valid qualified signature in the dark theme") {
		captureScreenshot("signature-panel-valid-dark", darkTheme = true, interaction = openSignature) {
			SignaturePanel(SignaturePanelState.Loaded(validReport), onLoadSignatures = {})
		}
	}

	test("an indeterminate signature with a warning") {
		captureScreenshot("signature-panel-indeterminate", interaction = openSignature) {
			SignaturePanel(SignaturePanelState.Loaded(indeterminateReport), onLoadSignatures = {})
		}
	}
})
