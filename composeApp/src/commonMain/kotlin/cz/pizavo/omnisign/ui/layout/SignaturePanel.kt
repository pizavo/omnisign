package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.SignatureTrustTier
import cz.pizavo.omnisign.domain.model.validation.TimestampValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Accordion
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.ButtonVariant
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.rememberAccordionState
import cz.pizavo.omnisign.ui.model.SignaturePanelState
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_chevron_down
import omnisign.composeapp.generated.resources.icon_rosette
import omnisign.composeapp.generated.resources.icon_shield_check
import omnisign.composeapp.generated.resources.icon_shield_exclamation
import omnisign.composeapp.generated.resources.icon_shield_question
import omnisign.composeapp.generated.resources.icon_signature
import omnisign.composeapp.generated.resources.rosette_check
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Content of the Signature side panel.
 *
 * Displays a prompt in the [SignaturePanelState.Idle] state,
 * a progress indicator while [SignaturePanelState.Loading], the full validation
 * report when [SignaturePanelState.Loaded], or an error message on failure.
 *
 * @param state Current panel state from [cz.pizavo.omnisign.ui.viewmodel.SignatureViewModel].
 * @param onLoadSignatures Callback invoked when the user requests signature retrieval.
 */
@Composable
fun SignaturePanel(
    state: SignaturePanelState,
    onLoadSignatures: () -> Unit,
) {
    when (state) {
        is SignaturePanelState.Idle -> IdleContent(hasDocument = state.hasDocument)
        is SignaturePanelState.Loading -> LoadingContent()
        is SignaturePanelState.Loaded -> ReportContent(report = state.report)
        is SignaturePanelState.Error -> ErrorContent(message = state.message, onRetry = onLoadSignatures)
    }
}

/**
 * Idle state — prompts the user to open a document or use the refresh action.
 */
@Composable
private fun IdleContent(hasDocument: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.icon_signature),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = LumoTheme.colors.textSecondary,
        )
        Text(
            text = if (hasDocument) "Press the refresh button to retrieve signature information."
            else "Open a PDF document first.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
    }
}

/**
 * Loading indicator displayed while validation is running.
 */
@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Analysing signatures…",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
    }
}

/**
 * Error state with a retry button.
 */
@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.error,
        )
        Button(
            text = "Retry",
            variant = ButtonVariant.PrimaryOutlined,
            onClick = onRetry,
        )
    }
}

/**
 * Successfully loaded report – renders the overall result badge, document metadata,
 * a collapsible "Signatures" group, a collapsible "Document Timestamps" group, and
 * optional trusted-list warnings.
 */
@Composable
private fun ReportContent(report: ValidationReport) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverallResultBadge(result = report.overallResult)

        Spacer(modifier = Modifier.height(4.dp))

        LabelValue(label = "Document", value = report.documentName)
        LabelValue(label = "Validation time", value = report.validationTime.formatDateTime())

        if (report.signatures.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No signatures found in the document.",
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            SignaturesGroup(signatures = report.signatures)
        }

        if (report.timestamps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            DocumentTimestampsGroup(timestamps = report.timestamps)
        }

        if (report.tlWarnings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Trusted List Warnings", style = LumoTheme.typography.h4)
            report.tlWarnings.forEach { warning ->
                Text(
                    text = "⚠️ $warning",
                    style = LumoTheme.typography.body2,
                    color = LumoTheme.colors.warning,
                )
            }
        }
    }
}

/**
 * Colored badge indicating the overall validation result.
 */
@Composable
private fun OverallResultBadge(result: ValidationResult) {
    val (label, color, icon) = when (result) {
        ValidationResult.VALID -> Triple("VALID", LumoTheme.colors.success, Res.drawable.icon_shield_check)
        ValidationResult.INVALID -> Triple("INVALID", LumoTheme.colors.error, Res.drawable.icon_shield_exclamation)
        ValidationResult.INDETERMINATE -> Triple("INDETERMINATE", LumoTheme.colors.warning, Res.drawable.icon_shield_question)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = color,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = label, style = LumoTheme.typography.h3, color = color)
    }
}

/**
 * Top-level collapsible "Signatures" group whose shield icon reflects the aggregate
 * sign of all contained signatures.
 */
@Composable
private fun SignaturesGroup(signatures: List<SignatureValidationResult>) {
    val aggregateIndication = aggregateSignatureIndication(signatures)

    SectionAccordion(
        title = "Signatures (${signatures.size})",
        indication = aggregateIndication,
        initiallyExpanded = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            signatures.forEachIndexed { index, sig ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(2.dp))
                }
                SignatureAccordion(index = index, total = signatures.size, signature = sig)
            }
        }
    }
}

/**
 * Collapsible section for a single signature. Contains the signature detail fields,
 * a nested collapsible "Certificate" section, and a nested collapsible "Timestamps"
 * section when the signature carries embedded timestamps.
 */
@Composable
private fun SignatureAccordion(
    index: Int,
    total: Int,
    signature: SignatureValidationResult,
) {
    SectionAccordion(
        title = "Signature ${index + 1} of $total — ${signature.signedBy}",
        indication = signature.indication,
        initiallyExpanded = false,
        trailingIcon = trustTierIcon(signature.trustTier),
        trailingTint = trustTierColor(signature.trustTier),
    ) {
        Column(
            modifier = Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelValue(label = "Indication", value = formatIndication(signature.indication))
            signature.subIndication?.let { LabelValue(label = "Sub-indication", value = it) }
            LabelValue(label = "Signed by", value = signature.signedBy)
            LabelValue(label = "Level", value = signature.signatureLevel)
            LabelValue(label = "Time", value = signature.signatureTime.formatDateTime())
            signature.signatureQualification?.let { LabelValue(label = "Qualification", value = it) }
            if (signature.trustTier != SignatureTrustTier.NOT_QUALIFIED) {
                LabelValue(label = "Trust", value = signature.trustTier.label)
            }
            signature.hashAlgorithm?.let { LabelValue(label = "Hash algorithm", value = it) }
            signature.encryptionAlgorithm?.let { LabelValue(label = "Encryption", value = it) }

            MessageList(title = "Errors", messages = signature.errors, color = LumoTheme.colors.error)
            MessageList(title = "Warnings", messages = signature.warnings, color = LumoTheme.colors.warning)
            MessageList(
                title = "Qualification Errors",
                messages = signature.qualificationErrors,
                color = LumoTheme.colors.error,
            )
            MessageList(
                title = "Qualification Warnings",
                messages = signature.qualificationWarnings,
                color = LumoTheme.colors.warning,
            )

            Spacer(modifier = Modifier.height(4.dp))
            CertificateAccordion(signature = signature)

            signature.timestamps.firstOrNull()?.let { ts ->
                Spacer(modifier = Modifier.height(4.dp))
                SignatureTimestampAccordion(timestamp = ts)
            }
        }
    }
}

/**
 * Nested collapsible section displaying the signing certificate details.
 */
@Composable
private fun CertificateAccordion(signature: SignatureValidationResult) {
    NestedAccordion(title = "Certificate") {
        Column(
            modifier = Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelValue(label = "Subject", value = signature.certificate.subjectDN)
            LabelValue(label = "Issuer", value = signature.certificate.issuerDN)
            LabelValue(label = "Serial", value = signature.certificate.serialNumber)
            LabelValue(label = "Valid from", value = signature.certificate.validFrom.formatDate())
            LabelValue(label = "Valid to", value = signature.certificate.validTo.formatDate())
            if (signature.certificate.keyUsages.isNotEmpty()) {
                LabelValue(label = "Key usages", value = signature.certificate.keyUsages.joinToString())
            }
            signature.certificate.publicKeyAlgorithm?.let { LabelValue(label = "Public key", value = it) }
            signature.certificate.sha256Fingerprint?.let { LabelValue(label = "SHA-256", value = it) }
        }
    }
}

/**
 * Collapsible section for the single signature-level timestamp.
 *
 * PAdES allows at most one signature timestamp per signature, so this renders
 * a flat accordion instead of a nested group. The shield icon reflects the
 * timestamp's own validation indication.
 */
@Composable
private fun SignatureTimestampAccordion(timestamp: TimestampValidationResult) {
    SectionAccordion(
        title = "Signature timestamp",
        indication = timestamp.indication,
        initiallyExpanded = false,
    ) {
        Column(
            modifier = Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelValue(label = "Indication", value = formatIndication(timestamp.indication))
            timestamp.subIndication?.let { LabelValue(label = "Sub-indication", value = it) }
            LabelValue(label = "Production time", value = timestamp.productionTime.formatDateTime())
            timestamp.qualification?.let { LabelValue(label = "Qualification", value = it) }
            timestamp.tsaSubjectDN?.let { LabelValue(label = "TSA", value = it) }

            MessageList(title = "Errors", messages = timestamp.errors, color = LumoTheme.colors.error)
            MessageList(title = "Warnings", messages = timestamp.warnings, color = LumoTheme.colors.warning)
        }
    }
}

/**
 * Top-level collapsible "Document Timestamps" group for timestamps not associated
 * with a specific signature (e.g., archive timestamps). The shield icon reflects
 * the aggregate indication of all contained timestamps.
 */
@Composable
private fun DocumentTimestampsGroup(timestamps: List<TimestampValidationResult>) {
    val aggregateIndication = aggregateTimestampIndication(timestamps)

    SectionAccordion(
        title = "Document Timestamps (${timestamps.size})",
        indication = aggregateIndication,
        initiallyExpanded = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            timestamps.forEachIndexed { index, ts ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(2.dp))
                }
                TimestampAccordion(index = index, total = timestamps.size, timestamp = ts)
            }
        }
    }
}

/**
 * Collapsible section for a single timestamp entry.
 */
@Composable
private fun TimestampAccordion(
    index: Int,
    total: Int,
    timestamp: TimestampValidationResult,
) {
    SectionAccordion(
        title = "Timestamp ${index + 1} of $total — ${timestamp.type}",
        indication = timestamp.indication,
        initiallyExpanded = false,
    ) {
        Column(
            modifier = Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelValue(label = "Indication", value = formatIndication(timestamp.indication))
            timestamp.subIndication?.let { LabelValue(label = "Sub-indication", value = it) }
            LabelValue(label = "Production time", value = timestamp.productionTime.formatDateTime())
            timestamp.qualification?.let { LabelValue(label = "Qualification", value = it) }
            timestamp.tsaSubjectDN?.let { LabelValue(label = "TSA", value = it) }

            MessageList(title = "Errors", messages = timestamp.errors, color = LumoTheme.colors.error)
            MessageList(title = "Warnings", messages = timestamp.warnings, color = LumoTheme.colors.warning)
        }
    }
}

/**
 * Accordion header with a shield icon reflecting the [indication], a title, an optional
 * [trailingIcon] (e.g. a rosette for qualified signatures), and a rotating chevron.
 * Used for top-level groups and individual signature/timestamp items.
 */
@Composable
private fun SectionAccordion(
    title: String,
    indication: ValidationIndication,
    initiallyExpanded: Boolean,
    trailingIcon: DrawableResource? = null,
    trailingTint: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val state = rememberAccordionState(expanded = initiallyExpanded)
    val chevronRotation by animateFloatAsState(
        targetValue = if (state.expanded) 180f else 0f,
        label = "chevron",
    )

    Accordion(
        state = state,
        headerContent = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(indicationIcon(indication)),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = indicationColor(indication),
                )
                Text(
                    text = title,
                    style = LumoTheme.typography.h4,
                    modifier = Modifier.weight(1f),
                )
                if (trailingIcon != null) {
                    Icon(
                        painter = painterResource(trailingIcon),
                        contentDescription = "Qualified",
                        modifier = Modifier.size(18.dp),
                        tint = trailingTint,
                    )
                }
                Icon(
                    painter = painterResource(Res.drawable.icon_chevron_down),
                    contentDescription = if (state.expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp).rotate(chevronRotation),
                    tint = LumoTheme.colors.textSecondary,
                )
            }
        },
        bodyContent = {
            Column(modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) {
                content()
            }
        },
    )
}

/**
 * Lightweight nested accordion without a shield icon. Used for subsections such as
 * "Certificate" inside a signature.
 */
@Composable
private fun NestedAccordion(
    title: String,
    content: @Composable () -> Unit,
) {
    val state = rememberAccordionState(expanded = false)
    val chevronRotation by animateFloatAsState(
        targetValue = if (state.expanded) 180f else 0f,
        label = "chevron",
    )

    Accordion(
        state = state,
        headerContent = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = LumoTheme.typography.label1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(Res.drawable.icon_chevron_down),
                    contentDescription = if (state.expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(14.dp).rotate(chevronRotation),
                    tint = LumoTheme.colors.textSecondary,
                )
            }
        },
        bodyContent = {
            Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)) {
                content()
            }
        },
    )
}

/**
 * A horizontal label–value pair.
 */
@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "$label:", style = LumoTheme.typography.body2, color = LumoTheme.colors.textSecondary)
        Text(text = value, style = LumoTheme.typography.body2)
    }
}

/**
 * Renders a titled list of messages if non-empty.
 */
@Composable
private fun MessageList(
    title: String,
    messages: List<String>,
    color: Color,
) {
    if (messages.isEmpty()) return
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(text = title, style = LumoTheme.typography.label1, color = color)
        messages.forEach { msg ->
            Text(
                text = "• $msg",
                style = LumoTheme.typography.body2,
                color = color,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Map a [ValidationIndication] to a human-readable label.
 */
private fun formatIndication(indication: ValidationIndication): String = when (indication) {
    ValidationIndication.TOTAL_PASSED -> "PASSED"
    ValidationIndication.TOTAL_FAILED -> "FAILED"
    ValidationIndication.INDETERMINATE -> "INDETERMINATE"
}

/**
 * Map a [ValidationIndication] to the appropriate shield icon resource.
 */
private fun indicationIcon(indication: ValidationIndication): DrawableResource = when (indication) {
    ValidationIndication.TOTAL_PASSED -> Res.drawable.icon_shield_check
    ValidationIndication.TOTAL_FAILED -> Res.drawable.icon_shield_exclamation
    ValidationIndication.INDETERMINATE -> Res.drawable.icon_shield_question
}

/**
 * Map a [ValidationIndication] to the appropriate theme color.
 */
@Composable
private fun indicationColor(indication: ValidationIndication) = when (indication) {
    ValidationIndication.TOTAL_PASSED -> LumoTheme.colors.success
    ValidationIndication.TOTAL_FAILED -> LumoTheme.colors.error
    ValidationIndication.INDETERMINATE -> LumoTheme.colors.warning
}

/**
 * Map a [SignatureTrustTier] to the appropriate rosette icon resource, or `null`
 * when no rosette should be displayed.
 */
private fun trustTierIcon(tier: SignatureTrustTier): DrawableResource? = when (tier) {
    SignatureTrustTier.QUALIFIED_QSCD -> Res.drawable.rosette_check
    SignatureTrustTier.QUALIFIED -> Res.drawable.icon_rosette
    SignatureTrustTier.NOT_QUALIFIED -> null
}

/**
 * Map a [SignatureTrustTier] to the appropriate theme-aware tint color.
 *
 * Uses dedicated icon colors from [LumoTheme.colors.icons] so rosette hues
 * are independent of the semantic palette (success / error / warning) used by the
 * validation-indication shields.
 */
@Composable
private fun trustTierColor(tier: SignatureTrustTier): Color = when (tier) {
    SignatureTrustTier.QUALIFIED_QSCD -> LumoTheme.colors.icons.trustQualifiedQscd
    SignatureTrustTier.QUALIFIED -> LumoTheme.colors.icons.trustQualified
    SignatureTrustTier.NOT_QUALIFIED -> Color.Unspecified
}

/**
 * Derive an aggregate [ValidationIndication] for a list of signatures.
 * All passed → [ValidationIndication.TOTAL_PASSED], any failed →
 * [ValidationIndication.TOTAL_FAILED], otherwise [ValidationIndication.INDETERMINATE].
 */
private fun aggregateSignatureIndication(
    signatures: List<SignatureValidationResult>,
): ValidationIndication = when {
    signatures.all { it.indication == ValidationIndication.TOTAL_PASSED } -> ValidationIndication.TOTAL_PASSED
    signatures.any { it.indication == ValidationIndication.TOTAL_FAILED } -> ValidationIndication.TOTAL_FAILED
    else -> ValidationIndication.INDETERMINATE
}

/**
 * Derive an aggregate [ValidationIndication] for a list of timestamps.
 * All passed → [ValidationIndication.TOTAL_PASSED], any failed →
 * [ValidationIndication.TOTAL_FAILED], otherwise [ValidationIndication.INDETERMINATE].
 */
private fun aggregateTimestampIndication(
    timestamps: List<TimestampValidationResult>,
): ValidationIndication = when {
    timestamps.all { it.indication == ValidationIndication.TOTAL_PASSED } -> ValidationIndication.TOTAL_PASSED
    timestamps.any { it.indication == ValidationIndication.TOTAL_FAILED } -> ValidationIndication.TOTAL_FAILED
    else -> ValidationIndication.INDETERMINATE
}
