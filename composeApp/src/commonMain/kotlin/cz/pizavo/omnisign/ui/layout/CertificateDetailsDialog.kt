package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.signature.CertificateChainLink
import cz.pizavo.omnisign.domain.model.signature.CertificateTrustSource
import cz.pizavo.omnisign.domain.model.signature.displayLabel
import cz.pizavo.omnisign.domain.model.signature.roleLabel
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Dialog
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.SelectableContent
import cz.pizavo.omnisign.lumo.components.Surface
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.VerticalDivider
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.ui.model.CertificateExportFormat
import cz.pizavo.omnisign.ui.model.localized
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.chooseSaveDestination
import cz.pizavo.omnisign.ui.platform.writeBytesToPath
import cz.pizavo.omnisign.ui.toast.LocalToastService
import cz.pizavo.omnisign.ui.toast.ToastMessage
import kotlin.math.floor
import kotlinx.coroutines.launch
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CertNavWidth = 240.dp
private val CertNavItemShape = RoundedCornerShape(6.dp)
private val CertRowHeight = 52.dp
private val CertRailWidth = 20.dp
private val CertIconSize = 16.dp
private val CertIconGap = 3.dp
private val CertConnectorWidth = 1.5.dp
private val CertConnectorDash = 6.dp
private val CertConnectorGap = 4.dp
private val CertTrustMenuWidth = 200.dp

/**
 * Modal showing a certificate [chain] in full. A left navigation panel lists every certificate as
 * its own row — the trust anchor at the top, intermediates below it, the end-entity (the signing or
 * timestamping certificate) at the bottom — and the right pane renders the selected certificate's
 * complete, parsed dump (every field and extension), scrollable and selectable so any value (a long
 * DN, an ASN.1 dump, a fingerprint) can be copied.
 *
 * Reused for both a signature's chain and a timestamp's chain. [trustRole] reflects which and is the
 * trust role granted when a certificate is added to the trust store from here:
 * [TrustedCertificateType.CA] for a signature's chain, [TrustedCertificateType.TSA] for a
 * timestamp's.
 *
 * The whole dialog is hosted under [DisableSelection]. This dialog is opened from inside the
 * validation report's own [SelectableContent] (see `ReportContent`), and the lumo [Dialog] renders
 * its body in a separate window layer while remaining a subcomposition — so the report's
 * [androidx.compose.foundation.text.selection.SelectionContainer] would otherwise leak in via the
 * composition local and try to select text that lives in a different layout hierarchy, crashing
 * with "layouts are not part of the same hierarchy". [DisableSelection] clears the inherited
 * registrar; the detail pane's [SelectableContent] then establishes its own self-contained scope.
 *
 * @param chain The certificate chain to render, leaf-first (end-entity at index 0, trust anchor
 *   last).
 * @param trustRole Trust role granted when a certificate is added to the trust store from this
 *   dialog, derived from what the chain anchors (a signature or a timestamp).
 * @param onDismiss Invoked to close the dialog.
 */
@Composable
fun CertificateDetailsDialog(
    chain: List<CertificateChainLink>,
    trustRole: TrustedCertificateType,
    onDismiss: () -> Unit,
) {
    var selectedIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val toast = LocalToastService.current
    val trustAdder = LocalTrustedCertificateAdder.current

    Dialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 660.dp, max = 900.dp).heightIn(min = 400.dp, max = 720.dp),
    ) {
        DisableSelection {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.icon_certificate_2),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = LumoTheme.colors.textSecondary,
                    )
                    Text(text = stringResource(Res.string.certdetails_title), style = LumoTheme.typography.h3, modifier = Modifier.weight(1f))
                    chain.getOrNull(selectedIndex)?.let { link ->
                        if (trustAdder != null) {
                            CertificateTrustButton(adder = trustAdder, der = link.der, trustRole = trustRole)
                        }
                    }
                    TooltipBox(
                        tooltip = { Tooltip { Text(text = stringResource(Res.string.certdetails_export_tooltip)) } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(
                            variant = IconButtonVariant.Ghost,
                            onClick = {
                                chain.getOrNull(selectedIndex)?.let { link ->
                                    scope.launch {
                                        val destination = chooseSaveDestination(
                                            suggestedName = certificateFileName(link),
                                            extension = CertificateExportFormat.Cer.extension,
                                            allowedExtensions = CertificateExportFormat.entries.map { it.extension }.toSet(),
                                        )
                                        if (destination != null) {
                                            val format = CertificateExportFormat
                                                .forExtension(destination.substringAfterLast('.', ""))
                                                ?: CertificateExportFormat.Cer
                                            val error = writeBytesToPath(destination, format.encode(link.der))
                                            toast?.show(
                                                ToastMessage(
                                                    if (error == null) getString(Res.string.certdetails_export_success) else getString(Res.string.certdetails_export_failed, error),
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.icon_download),
                                contentDescription = stringResource(Res.string.certdetails_export_tooltip),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(variant = IconButtonVariant.Ghost, onClick = onDismiss) {
                        Icon(
                            painter = painterResource(Res.drawable.icon_x),
                            contentDescription = stringResource(Res.string.action_close),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                HorizontalDivider()

                Row(modifier = Modifier.weight(1f)) {
                    CertificateChainNav(
                        chain = chain,
                        selectedIndex = selectedIndex,
                        onSelect = { selectedIndex = it },
                        trustRole = trustRole,
                    )
                    VerticalDivider()
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        key(selectedIndex) {
                            CertificateDetailPane(link = chain.getOrNull(selectedIndex))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Left navigation panel: one row per certificate, ordered top-down for reading — the trust anchor
 * first, then each intermediate, with the end-entity certificate last. Each row's role label is
 * derived from the certificate's position in [chain] (which is leaf-first), so the displayed order is
 * the reverse of the stored order; the leaf is labelled per [trustRole] (a signing certificate for a
 * signature's chain, a timestamp certificate for a timestamp's).
 *
 * @param chain The certificate chain, leaf-first (end-entity at index 0).
 * @param selectedIndex Index into [chain] of the currently shown certificate.
 * @param onSelect Invoked with a [chain] index when the user picks a row.
 * @param trustRole Distinguishes a signature's chain from a timestamp's; used only to label the leaf.
 */
@Composable
private fun CertificateChainNav(
    chain: List<CertificateChainLink>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    trustRole: TrustedCertificateType,
) {
    val anchorIndex = chain.indexOfFirst { it.trustedVia.isNotEmpty() }
    val rowHeights = remember { mutableStateMapOf<Int, Float>() }
    VerticalScrollableColumn(
        modifier = Modifier.width(CertNavWidth).fillMaxHeight(),
        contentPadding = PaddingValues(8.dp),
    ) {
        for ((displayPos, index) in chain.indices.reversed().withIndex()) {
            val link = chain[index]
            val role = link.roleLabel(
                isLeaf = index == 0,
                isTop = index == chain.lastIndex,
                leafRole = trustRole,
            ).localized()
            val icon: DrawableResource
            val iconTint: Color
            when {
                index == 0 -> {
                    icon = Res.drawable.icon_key
                    iconTint = LumoTheme.colors.icons.certSigningKey
                }
                index == chain.lastIndex -> {
                    icon = Res.drawable.icon_circle_filled
                    iconTint = LumoTheme.colors.icons.certRoot
                }
                else -> {
                    icon = Res.drawable.icon_circle
                    iconTint = LumoTheme.colors.icons.certIntermediate
                }
            }
            CertificateChainNavItem(
                title = link.commonName ?: link.subjectDN,
                role = role,
                icon = icon,
                iconTint = iconTint,
                trustedVia = link.trustedVia,
                isTrustAnchor = index == anchorIndex,
                connectorAbove = index != chain.lastIndex,
                connectorBelow = index != 0,
                rowTopFromFirst = (0 until displayPos).fold(0f) { acc, d -> acc + (rowHeights[d] ?: 0f) },
                onMeasuredHeight = { rowHeights[displayPos] = it },
                isSelected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/**
 * A single certificate row in the navigation panel: a role icon (a key for the signing certificate,
 * a filled circle for the root, a hollow circle for an intermediate), tinted with its role colour,
 * beside the role label and the certificate's common name. A dashed line is drawn through the icon
 * rail — above the icon unless this is the top (root) row, below it unless this is the bottom
 * (signing) row — so the stacked rows read as one connected chain. Each stub stops the same fixed
 * clearance from the icon on both sides, and its dashes are laid on a single chain-wide grid offset
 * by [rowTopFromFirst] — the row's cumulative top from the first row — so they stay evenly spaced
 * across the icon gaps and the row boundaries, reading as one continuous line down the whole chain
 * even when rows differ in height. When [isSelected] the row gets the primary-tinted background and
 * primary text, matching the settings dialog's navigation selection style; the role icon keeps its
 * colour regardless of selection. A trailing trust indicator marks trusted certificates — a blue
 * anchor on the effective trust anchor, a green check on any other trusted certificate — with a
 * tooltip naming the source(s).
 *
 * @param title The certificate's common name (or full DN when it has no CN).
 * @param role The certificate's position-derived role label.
 * @param icon The role icon shown at the leading edge of the row.
 * @param iconTint The role colour applied to [icon].
 * @param trustedVia The trust sources vouching for this certificate under the validation
 *   environment; empty when it is not trusted (no indicator is shown).
 * @param isTrustAnchor Whether this is the effective trust anchor — the lowest trusted certificate
 *   in the chain, where the path's trust is realised. Shown with a blue anchor; other trusted certs
 *   get a green check.
 * @param connectorAbove Whether to draw the dashed connector from the row's top edge to the icon.
 * @param connectorBelow Whether to draw the dashed connector from the icon to the row's bottom edge.
 * @param rowTopFromFirst This row's cumulative top offset (px) from the first row's top, summed from
 *   the measured heights of the rows above it; offsets the shared dash grid so the connector reads as
 *   one continuous line down the whole chain regardless of differing row heights.
 * @param onMeasuredHeight Reports this row's measured pixel height so the rows below it can compute
 *   their [rowTopFromFirst].
 * @param isSelected Whether this row is the active selection.
 * @param onClick Invoked when the row is clicked.
 */
@Composable
private fun CertificateChainNavItem(
    title: String,
    role: String,
    icon: DrawableResource,
    iconTint: Color,
    trustedVia: List<CertificateTrustSource>,
    isTrustAnchor: Boolean,
    connectorAbove: Boolean,
    connectorBelow: Boolean,
    rowTopFromFirst: Float,
    onMeasuredHeight: (Float) -> Unit,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isSelected) LumoTheme.colors.primary.copy(alpha = 0.15f) else LumoTheme.colors.surface
    val roleColor = if (isSelected) LumoTheme.colors.primary else LumoTheme.colors.textSecondary
    val titleColor = if (isSelected) LumoTheme.colors.primary else LumoTheme.colors.text
    val connectorColor = LumoTheme.colors.icons.certConnector

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CertRowHeight)
            .onSizeChanged { onMeasuredHeight(it.height.toFloat()) }
            .clip(CertNavItemShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .drawBehind {
                val centerX = CertRailWidth.toPx() / 2f
                val rowHeight = size.height
                val centerY = rowHeight / 2f
                val clearance = CertIconSize.toPx() / 2f + CertIconGap.toPx()
                val period = CertConnectorDash.toPx() + CertConnectorGap.toPx()
                val dashLength = CertConnectorDash.toPx()
                val stroke = CertConnectorWidth.toPx()
                if (connectorAbove) {
                    drawConnectorSegment(centerX, 0f, centerY - clearance, rowTopFromFirst, period, dashLength, connectorColor, stroke)
                }
                if (connectorBelow) {
                    drawConnectorSegment(centerX, centerY + clearance, rowHeight, rowTopFromFirst, period, dashLength, connectorColor, stroke)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(CertRailWidth),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(CertIconSize),
                tint = iconTint,
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = role,
                    style = LumoTheme.typography.body2,
                    color = roleColor,
                    modifier = Modifier.weight(1f),
                )
                if (trustedVia.isNotEmpty()) {
                    TooltipBox(
                        tooltip = {
                            Tooltip {
                                val sources = mutableListOf<String>()
                                for (source in trustedVia) {
                                    sources.add(source.displayLabel().localized())
                                }
                                Text(
                                    text = stringResource(
                                        if (isTrustAnchor) Res.string.certdetails_trusted_via_anchor else Res.string.certdetails_trusted_via_other,
                                        sources.joinToString(", "),
                                    ),
                                )
                            }
                        },
                        state = rememberTooltipState(),
                    ) {
                        Icon(
                            painter = painterResource(if (isTrustAnchor) Res.drawable.icon_anchor else Res.drawable.icon_check),
                            contentDescription = if (isTrustAnchor) stringResource(Res.string.certdetails_trust_anchor_label) else stringResource(Res.string.certdetails_trusted_label),
                            modifier = Modifier.size(14.dp),
                            tint = if (isTrustAnchor) LumoTheme.colors.icons.trustQualified else LumoTheme.colors.success,
                        )
                    }
                }
            }
            Text(
                text = title,
                style = LumoTheme.typography.label1,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Draw one vertical run of the certificate-chain connector: the line at x = [centerX] over this row's
 * local vertical range [[start], [end]] (in pixels). Rather than relying on a platform dash
 * `PathEffect` (whose phase is awkward to keep continuous across rows of differing height), the dashes
 * are laid on a single chain-wide grid — a [dashLength]-pixel dash every [period] pixels, measured
 * from the top of the first row — and this run is shifted onto that grid by [globalOffset] (the row's
 * cumulative top). Each on-grid dash is clipped to the run and stroked as its own short line, so
 * consecutive rows contribute aligned pieces of one continuous dashed line. Draws nothing when
 * [end] is not greater than [start].
 *
 * @param centerX X position of the vertical line, in this row's pixels.
 * @param start Top of the run, in this row's pixels.
 * @param end Bottom of the run, in this row's pixels.
 * @param globalOffset This row's top offset from the first row's top, aligning the run to the grid.
 * @param period Centre-to-centre dash spacing (dash length plus gap), in pixels.
 * @param dashLength Length of each drawn dash, in pixels.
 * @param color Connector colour.
 * @param stroke Line thickness, in pixels.
 */
private fun DrawScope.drawConnectorSegment(
    centerX: Float,
    start: Float,
    end: Float,
    globalOffset: Float,
    period: Float,
    dashLength: Float,
    color: Color,
    stroke: Float,
) {
    if (end <= start) return
    val globalStart = globalOffset + start
    val globalEnd = globalOffset + end
    var dashIndex = floor(globalStart / period).toInt()
    while (dashIndex * period < globalEnd) {
        val dashTop = maxOf(dashIndex * period, globalStart)
        val dashBottom = minOf(dashIndex * period + dashLength, globalEnd)
        if (dashBottom > dashTop) {
            drawLine(
                color = color,
                start = Offset(centerX, dashTop - globalOffset),
                end = Offset(centerX, dashBottom - globalOffset),
                strokeWidth = stroke,
            )
        }
        dashIndex++
    }
}

/**
 * Right pane rendering the selected certificate's complete parsed dump, grouped into sections and
 * scrollable. Wrapped in [SelectableContent] (re-enabling selection inside the dialog's
 * [DisableSelection] scope) so every value can be copied. Renders nothing when [link] is null.
 *
 * @param link The certificate whose [CertificateChainLink.details] are shown.
 */
@Composable
private fun CertificateDetailPane(link: CertificateChainLink?) {
    if (link == null) return
    VerticalScrollableColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
    ) {
        SelectableContent {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                link.details.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = localizedCertLabel(section.title), style = LumoTheme.typography.h4)
                        section.fields.forEach { field ->
                            CertificateFieldRow(label = localizedCertLabel(field.label), value = field.value)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One certificate field: the label in the secondary colour above its (possibly multi-line) value.
 */
@Composable
private fun CertificateFieldRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text(text = label, style = LumoTheme.typography.label1, color = LumoTheme.colors.textSecondary)
        Text(text = value, style = LumoTheme.typography.body2)
    }
}

/**
 * Localized display label for a certificate-detail section title or field name. The JVM
 * `CertificateDetailsExtractor` emits these in English (and as raw dotted OIDs for non-standard
 * attributes/extensions); the known section titles, field labels, distinguished-name attributes and
 * extension names are mapped to the active locale here — mirroring the panel's value mappings — with
 * a trailing "(critical)" marker translated and anything unrecognised (a raw OID) shown verbatim.
 * Field *values* are never localized. The plain-text report and JSON export keep the English labels.
 */
@Composable
private fun localizedCertLabel(label: String): String {
    val critical = label.endsWith(" (critical)")
    val base = if (critical) label.removeSuffix(" (critical)") else label
    val localizedBase = when (base) {
        "General" -> stringResource(Res.string.certfield_section_general)
        "Subject" -> stringResource(Res.string.certfield_section_subject)
        "Issuer" -> stringResource(Res.string.certfield_section_issuer)
        "Validity" -> stringResource(Res.string.certfield_section_validity)
        "Public Key" -> stringResource(Res.string.certfield_section_public_key)
        "Extensions" -> stringResource(Res.string.certfield_section_extensions)
        "Fingerprints" -> stringResource(Res.string.certfield_section_fingerprints)
        "Version" -> stringResource(Res.string.certfield_version)
        "Serial Number" -> stringResource(Res.string.certfield_serial_number)
        "Signature Algorithm" -> stringResource(Res.string.certfield_signature_algorithm)
        "Not Before" -> stringResource(Res.string.certfield_not_before)
        "Not After" -> stringResource(Res.string.certfield_not_after)
        "Algorithm" -> stringResource(Res.string.certfield_algorithm)
        "Key Size" -> stringResource(Res.string.certfield_key_size)
        "Common Name (CN)" -> stringResource(Res.string.certfield_dn_cn)
        "Organization (O)" -> stringResource(Res.string.certfield_dn_o)
        "Organizational Unit (OU)" -> stringResource(Res.string.certfield_dn_ou)
        "Country (C)" -> stringResource(Res.string.certfield_dn_c)
        "Locality (L)" -> stringResource(Res.string.certfield_dn_l)
        "State/Province (ST)" -> stringResource(Res.string.certfield_dn_st)
        "Street" -> stringResource(Res.string.certfield_dn_street)
        "Pseudonym" -> stringResource(Res.string.certfield_dn_pseudonym)
        "Title" -> stringResource(Res.string.certfield_dn_title)
        "Given Name" -> stringResource(Res.string.certfield_dn_given_name)
        "Surname" -> stringResource(Res.string.certfield_dn_surname)
        "Description" -> stringResource(Res.string.certfield_dn_description)
        "Business Category" -> stringResource(Res.string.certfield_dn_business_category)
        "Postal Code" -> stringResource(Res.string.certfield_dn_postal_code)
        "Postal Address" -> stringResource(Res.string.certfield_dn_postal_address)
        "Organization Identifier" -> stringResource(Res.string.certfield_dn_org_identifier)
        "Email" -> stringResource(Res.string.certfield_dn_email)
        "Domain Component (DC)" -> stringResource(Res.string.certfield_dn_dc)
        "User ID (UID)" -> stringResource(Res.string.certfield_dn_uid)
        "Key Usage" -> stringResource(Res.string.certfield_ext_key_usage)
        "Extended Key Usage" -> stringResource(Res.string.certfield_ext_eku)
        "Basic Constraints" -> stringResource(Res.string.certfield_ext_basic_constraints)
        "Subject Alternative Name" -> stringResource(Res.string.certfield_ext_san)
        "Issuer Alternative Name" -> stringResource(Res.string.certfield_ext_ian)
        "CRL Distribution Points" -> stringResource(Res.string.certfield_ext_crl_dp)
        "Authority Information Access" -> stringResource(Res.string.certfield_ext_aia)
        "Certificate Policies" -> stringResource(Res.string.certfield_ext_cert_policies)
        "Subject Key Identifier" -> stringResource(Res.string.certfield_ext_ski)
        "Authority Key Identifier" -> stringResource(Res.string.certfield_ext_aki)
        "QC Statements" -> stringResource(Res.string.certfield_ext_qc_statements)
        "Name Constraints" -> stringResource(Res.string.certfield_ext_name_constraints)
        "Policy Constraints" -> stringResource(Res.string.certfield_ext_policy_constraints)
        "Subject Directory Attributes" -> stringResource(Res.string.certfield_ext_subject_dir_attrs)
        "OCSP No-Check" -> stringResource(Res.string.certfield_ext_ocsp_nocheck)
        else -> base
    }
    return if (critical) "$localizedBase (${stringResource(Res.string.certfield_critical)})" else localizedBase
}

/**
 * Header control that adds the selected certificate ([der]) to the trust store via [adder] with the
 * given [trustRole]. With no profile selected it commits straight to the global scope on click; with
 * a profile selected it opens a small menu to choose Global or that profile. The outcome is reported
 * with a toast. Hidden entirely when no [TrustedCertificateAdder] is provided (e.g. platforms without
 * a trust store).
 */
@Composable
private fun CertificateTrustButton(
    adder: TrustedCertificateAdder,
    der: ByteArray,
    trustRole: TrustedCertificateType,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToastService.current
    var expanded by remember { mutableStateOf(false) }

    fun commit(toActiveProfile: Boolean) {
        expanded = false
        scope.launch {
            val error = adder.add(der, toActiveProfile, trustRole)
            toast?.show(
                ToastMessage(if (error == null) getString(Res.string.certdetails_trust_success) else getString(Res.string.certdetails_trust_failed, error)),
            )
        }
    }

    TooltipBox(
        tooltip = { Tooltip { Text(text = stringResource(Res.string.certdetails_trust_add_tooltip)) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            variant = IconButtonVariant.Ghost,
            onClick = { if (adder.activeProfileName == null) commit(toActiveProfile = false) else expanded = true },
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_shield_plus),
                contentDescription = stringResource(Res.string.certdetails_trust_add_tooltip),
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = LumoTheme.colors.surface,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, LumoTheme.colors.outline),
                ) {
                    Column(modifier = Modifier.width(CertTrustMenuWidth)) {
                        CertificateTrustMenuRow(label = stringResource(Res.string.certdetails_scope_global), onClick = { commit(toActiveProfile = false) })
                        adder.activeProfileName?.let { name ->
                            CertificateTrustMenuRow(
                                label = stringResource(Res.string.certdetails_scope_profile, name),
                                onClick = { commit(toActiveProfile = true) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One clickable scope row in the add-to-trusted menu. */
@Composable
private fun CertificateTrustMenuRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = LumoTheme.typography.body2, color = LumoTheme.colors.text)
    }
}

/**
 * A filesystem-safe file-name stem for exporting [link]: its common name with each run of
 * non-alphanumeric characters collapsed to a single underscore, falling back to "certificate" when
 * the certificate has no usable common name.
 */
private fun certificateFileName(link: CertificateChainLink): String {
    val base = link.commonName?.takeIf { it.isNotBlank() } ?: "certificate"
    return base.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifBlank { "certificate" }
}
