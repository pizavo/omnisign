package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import kotlin.time.Instant
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.ui.model.SignaturePanelState
import omnisign.composeapp.generated.resources.*
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
 * @param validationBlocked Whether a trusted-list refresh the current configuration needs is in
 *   flight; when `true` a [TrustedListLoadingBar] is shown above the panel content.
 * @param trustedListLoadProgress Trusted-list load progress feeding the bar (determinate once the lists are known).
 */
@Composable
fun SignaturePanel(
    state: SignaturePanelState,
    onLoadSignatures: () -> Unit,
    validationBlocked: Boolean = false,
    trustedListLoadProgress: TrustedListLoadProgress = TrustedListLoadProgress(),
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (validationBlocked) {
            TrustedListLoadingBar(progress = trustedListLoadProgress)
        }
        when (state) {
            is SignaturePanelState.Idle -> IdleContent(hasDocument = state.hasDocument)
            is SignaturePanelState.Loading -> LoadingContent()
            is SignaturePanelState.Loaded -> ReportContent(report = state.report, alertIfNotEuLotl = state.alertIfNotEuLotl)
            is SignaturePanelState.Error -> ErrorContent(message = state.message, onRetry = onLoadSignatures)
        }
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
 * Error state with a selectable message and a retry button.
 *
 * The failure message is wrapped in a [SelectableContent] so it can be copied (e.g.
 * into a bug report); the retry [Button] stays outside the selection scope.
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
        SelectableContent {
            Text(
                text = message,
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.error,
            )
        }
        Button(
            text = "Retry",
            variant = ButtonVariant.PrimaryOutlined,
            onClick = onRetry,
        )
    }
}

/**
 * Successfully loaded report. The whole report is wrapped in a [SelectableContent] so
 * its validation details — overall result, document metadata, signature and timestamp
 * fields, certificate details, and any trusted-list warnings — can be selected and
 * copied in one sweep. Collapsible section headers stay selectable too, so a copied
 * selection keeps its structure; only currently-expanded sections contribute text.
 */
@Composable
private fun ReportContent(report: ValidationReport, alertIfNotEuLotl: Boolean) {
    SelectableContent {
        ReportDetails(report = report, alertIfNotEuLotl = alertIfNotEuLotl)
    }
}

/**
 * Renders the report's content column: overall result badge, document metadata, the
 * collapsible "Signatures" and "Document Timestamps" groups, and optional trusted-list
 * warnings. Hosted inside [ReportContent]'s [SelectableContent].
 */
@Composable
private fun ReportDetails(report: ValidationReport, alertIfNotEuLotl: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverallResultBadge(report = report, alertIfNotEuLotl = alertIfNotEuLotl)

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
            SignaturesGroup(signatures = report.signatures, alertIfNotEuLotl = alertIfNotEuLotl)
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.icon_alert_warning),
                        contentDescription = null,
                        modifier = Modifier.padding(top = 3.dp).size(14.dp),
                        tint = LumoTheme.colors.warning,
                    )
                    Text(
                        text = warning,
                        style = LumoTheme.typography.body2,
                        color = LumoTheme.colors.warning,
                    )
                }
            }
        }
    }
}

/**
 * Colored badge indicating the overall validation result.
 *
 * When the report contains no signatures at all, a neutral "NO SIGNATURES" badge
 * with [Res.drawable.icon_shield_x] is shown instead of the normal result badge.
 *
 * When [ValidationReport.overallTrustTier] is qualified, an additional rosette icon
 * is rendered to the right of the label — the same rosette/color logic used for
 * individual signature accordions.
 */
@Composable
private fun OverallResultBadge(report: ValidationReport, alertIfNotEuLotl: Boolean) {
    if (report.signatures.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_shield_x),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = LumoTheme.colors.textSecondary,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "NO SIGNATURES",
                style = LumoTheme.typography.h3,
                color = LumoTheme.colors.textSecondary,
            )
        }
        return
    }

    val (label, color, icon) = when (report.overallResult) {
        ValidationResult.VALID -> Triple("VALID", LumoTheme.colors.success, Res.drawable.icon_shield_check)
        ValidationResult.INVALID -> Triple("INVALID", LumoTheme.colors.error, Res.drawable.icon_shield_exclamation)
        ValidationResult.INDETERMINATE -> Triple("INDETERMINATE", LumoTheme.colors.warning, Res.drawable.icon_shield_question)
    }
    val rosette = trustTierIcon(report.overallTrustTier)

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
        if (rosette != null) {
            Spacer(modifier = Modifier.size(8.dp))
            TooltipBox(
                tooltip = { Tooltip { Text(text = report.overallTrustTier.label) } },
                state = rememberTooltipState(),
            ) {
                Icon(
                    painter = painterResource(rosette),
                    contentDescription = report.overallTrustTier.label,
                    modifier = Modifier.size(22.dp),
                    tint = trustTierColor(report.overallTrustTier),
                )
            }
        }
        val euOnLotl = report.overallEuLotlBacked
        val euIcon = when {
            euOnLotl -> Res.drawable.icon_eu
            alertIfNotEuLotl && report.signatures.any { !it.euLotlBacked } -> Res.drawable.icon_eu_crossed
            else -> null
        }
        if (euIcon != null) {
            Spacer(modifier = Modifier.size(4.dp))
            TooltipBox(
                tooltip = { Tooltip { Text(text = if (euOnLotl) "On the EU LOTL" else "Not on the EU LOTL") } },
                state = rememberTooltipState(),
            ) {
                Icon(
                    painter = painterResource(euIcon),
                    contentDescription = if (euOnLotl) "On the EU LOTL" else "Not on the EU LOTL",
                    modifier = Modifier.size(22.dp),
                    tint = if (euOnLotl) LumoTheme.colors.icons.euStars else LumoTheme.colors.error,
                )
            }
        }
    }
}

/**
 * Top-level collapsible "Signatures" group whose shield icon reflects the aggregate
 * sign of all contained signatures.
 */
@Composable
private fun SignaturesGroup(signatures: List<SignatureValidationResult>, alertIfNotEuLotl: Boolean) {
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
                SignatureAccordion(index = index, total = signatures.size, signature = sig, alertIfNotEuLotl = alertIfNotEuLotl)
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
    alertIfNotEuLotl: Boolean,
) {
    SectionAccordion(
        title = "Signature ${index + 1} of $total — ${signature.signedBy}",
        indication = signature.indication,
        initiallyExpanded = false,
        precedingIcon = trustTierIcon(signature.trustTier),
        precedingTint = trustTierColor(signature.trustTier),
        precedingTooltip = signature.trustTier.takeIf { it != SignatureTrustTier.NOT_QUALIFIED }?.label,
        trailingIcon = when {
            signature.euLotlBacked -> Res.drawable.icon_eu
            alertIfNotEuLotl -> Res.drawable.icon_eu_crossed
            else -> null
        },
        trailingTint = if (signature.euLotlBacked) LumoTheme.colors.icons.euStars else LumoTheme.colors.error,
        trailingTooltip = when {
            signature.euLotlBacked -> "On the EU LOTL"
            alertIfNotEuLotl -> "Not on the EU LOTL"
            else -> null
        },
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
            MessageList(title = "Information", messages = signature.infos, color = LumoTheme.colors.textSecondary)
            MessageList(
                title = "Qualification Information",
                messages = signature.qualificationInfos,
                color = LumoTheme.colors.textSecondary,
            )

            Spacer(modifier = Modifier.height(4.dp))
            CertificateAccordion(signature = signature)

            signature.revocations.takeIf { it.isNotEmpty() }?.let { revocations ->
                Spacer(modifier = Modifier.height(4.dp))
                RevocationAccordion(revocations = revocations, asOf = signature.signatureTime)
            }

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
 * Nested collapsible section presenting the signing certificate's revocation evidence: a one-line
 * conclusion as of the best-signature-time, followed by every revocation check DSS found or
 * performed — each shown in full, so an embedded token and a live online check both appear rather
 * than one being chosen.
 *
 * @param asOf The point in time the conclusion is stated against (best-signature-time).
 */
@Composable
private fun RevocationAccordion(revocations: List<RevocationInfo>, asOf: Instant) {
    val title = if (revocations.size > 1) "Revocation checks (${revocations.size})" else "Revocation check"
    NestedAccordion(title = title) {
        Column(
            modifier = Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val revokedAtSigning = revocations.signingTimeRepresentative()?.revoked == true
            revocations.revocationConclusion(asOf)?.let { conclusion ->
                Text(
                    text = conclusion,
                    style = LumoTheme.typography.body2,
                    color = if (revokedAtSigning) LumoTheme.colors.error else LumoTheme.colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(2.dp))
            }
            revocations.forEachIndexed { index, revocation ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(2.dp))
                }
                RevocationEntry(revocation = revocation)
            }
        }
    }
}

/**
 * Structured fields for a single revocation token, sourced from the shared
 * [cz.pizavo.omnisign.domain.model.validation.displayRows] so the panel and the plain-text report
 * render identical labels and values.
 */
@Composable
private fun RevocationEntry(revocation: RevocationInfo) {
    revocation.displayRows().forEach { (label, value) ->
        LabelValue(label = label, value = value)
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
        initiallyExpanded = true,
        trailingIcon = if (timestamp.euLotlBacked) Res.drawable.icon_eu else null,
        trailingTint = LumoTheme.colors.icons.euStars,
        trailingTooltip = if (timestamp.euLotlBacked) "On the EU LOTL" else null,
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
            MessageList(title = "Information", messages = timestamp.infos, color = LumoTheme.colors.textSecondary)
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
        trailingIcon = if (timestamp.euLotlBacked) Res.drawable.icon_eu else null,
        trailingTint = LumoTheme.colors.icons.euStars,
        trailingTooltip = if (timestamp.euLotlBacked) "On the EU LOTL" else null,
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
            MessageList(title = "Information", messages = timestamp.infos, color = LumoTheme.colors.textSecondary)
        }
    }
}

/**
 * Accordion header with a shield icon reflecting the [indication], a title, then up to two
 * trailing decoration icons — [precedingIcon] (e.g. the qualification rosette) followed by
 * [trailingIcon] (e.g. the EU-LOTL emblem) — each with an optional hover tooltip, and a chevron.
 * Used for top-level groups and individual signature/timestamp items.
 */
@Composable
private fun SectionAccordion(
    title: String,
    indication: ValidationIndication,
    initiallyExpanded: Boolean,
    trailingIcon: DrawableResource? = null,
    trailingTint: Color = Color.Unspecified,
    trailingTooltip: String? = null,
    precedingIcon: DrawableResource? = null,
    precedingTint: Color = Color.Unspecified,
    precedingTooltip: String? = null,
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
                if (precedingIcon != null || trailingIcon != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (precedingIcon != null) {
                            TooltipBox(
                                tooltip = { Tooltip { Text(text = precedingTooltip ?: "EU qualified") } },
                                state = rememberTooltipState(),
                            ) {
                                Icon(
                                    painter = painterResource(precedingIcon),
                                    contentDescription = precedingTooltip ?: "EU qualified",
                                    modifier = Modifier.size(18.dp),
                                    tint = precedingTint,
                                )
                            }
                        }
                        if (trailingIcon != null) {
                            val iconContent = @Composable {
                                Icon(
                                    painter = painterResource(trailingIcon),
                                    contentDescription = trailingTooltip ?: "Qualified",
                                    modifier = Modifier.size(18.dp),
                                    tint = trailingTint,
                                )
                            }
                            if (trailingTooltip != null) {
                                TooltipBox(
                                    tooltip = { Tooltip { Text(text = trailingTooltip) } },
                                    state = rememberTooltipState(),
                                ) {
                                    iconContent()
                                }
                            } else {
                                iconContent()
                            }
                        }
                    }
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
 * A label–value pair rendered as a single [Text].
 *
 * Label and value are combined into one `AnnotatedString` — the label styled in the
 * secondary colour via a [SpanStyle] — so the pair is one selectable node and copies to
 * the clipboard on a single line ("label: value") instead of splitting across two when
 * selected inside the report's [SelectableContent].
 */
@Composable
private fun LabelValue(label: String, value: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = LumoTheme.colors.textSecondary)) { append("$label: ") }
            append(value)
        },
        modifier = Modifier.fillMaxWidth(),
        style = LumoTheme.typography.body2,
    )
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
    SignatureTrustTier.QUALIFIED_QSCD -> Res.drawable.icon_rosette_check
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
