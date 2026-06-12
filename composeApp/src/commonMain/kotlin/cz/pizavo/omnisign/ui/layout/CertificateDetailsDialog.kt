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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.signature.CertificateChainLink
import cz.pizavo.omnisign.domain.model.signature.CertificateTrustSource
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
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.chooseSaveDestination
import cz.pizavo.omnisign.ui.platform.writeBytesToPath
import cz.pizavo.omnisign.ui.toast.LocalToastService
import cz.pizavo.omnisign.ui.toast.ToastMessage
import kotlinx.coroutines.launch
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_anchor
import omnisign.composeapp.generated.resources.icon_certificate_2
import omnisign.composeapp.generated.resources.icon_check
import omnisign.composeapp.generated.resources.icon_circle
import omnisign.composeapp.generated.resources.icon_circle_filled
import omnisign.composeapp.generated.resources.icon_download
import omnisign.composeapp.generated.resources.icon_key
import omnisign.composeapp.generated.resources.icon_shield_plus
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

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
                    Text(text = "Certificate", style = LumoTheme.typography.h3, modifier = Modifier.weight(1f))
                    chain.getOrNull(selectedIndex)?.let { link ->
                        if (trustAdder != null) {
                            CertificateTrustButton(adder = trustAdder, der = link.der, trustRole = trustRole)
                        }
                    }
                    TooltipBox(
                        tooltip = { Tooltip { Text(text = "Export the selected certificate") } },
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
                                                    if (error == null) "Certificate exported." else "Export failed: $error",
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.icon_download),
                                contentDescription = "Export the selected certificate",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(variant = IconButtonVariant.Ghost, onClick = onDismiss) {
                        Icon(
                            painter = painterResource(Res.drawable.icon_x),
                            contentDescription = "Close",
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
    VerticalScrollableColumn(
        modifier = Modifier.width(CertNavWidth).fillMaxHeight(),
        contentPadding = PaddingValues(8.dp),
    ) {
        for (index in chain.indices.reversed()) {
            val link = chain[index]
            val role: String
            val icon: DrawableResource
            val iconTint: Color
            when {
                index == 0 -> {
                    role = if (trustRole == TrustedCertificateType.TSA) "Timestamp certificate" else "Signing certificate"
                    icon = Res.drawable.icon_key
                    iconTint = LumoTheme.colors.icons.certSigningKey
                }
                index == chain.lastIndex -> {
                    role = if (link.selfSigned) "Root CA" else "Certificate Authority"
                    icon = Res.drawable.icon_circle_filled
                    iconTint = LumoTheme.colors.icons.certRoot
                }
                else -> {
                    role = "Intermediate CA"
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
                displayPosition = chain.lastIndex - index,
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
 * clearance from the icon on both sides, and its dash pattern is phased by the row's
 * [displayPosition] so the dashes stay evenly spaced across the icon gaps and the row boundaries
 * (rather than restarting per row). When [isSelected] the row gets the primary-tinted background and
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
 * @param displayPosition Zero-based position of this row from the top (root = 0), used to phase the
 *   dashed connector so it reads as one continuous line down the whole chain.
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
    displayPosition: Int,
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
            .height(CertRowHeight)
            .clip(CertNavItemShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(CertRailWidth)
                .drawBehind {
                    val centerX = size.width / 2f
                    val rowHeight = size.height
                    val centerY = rowHeight / 2f
                    val clearance = CertIconSize.toPx() / 2f + CertIconGap.toPx()
                    val intervals = floatArrayOf(CertConnectorDash.toPx(), CertConnectorGap.toPx())
                    val stroke = CertConnectorWidth.toPx()
                    val rowTop = displayPosition * rowHeight
                    if (connectorAbove) {
                        drawLine(
                            color = connectorColor,
                            start = Offset(centerX, 0f),
                            end = Offset(centerX, centerY - clearance),
                            strokeWidth = stroke,
                            pathEffect = PathEffect.dashPathEffect(intervals, rowTop),
                        )
                    }
                    if (connectorBelow) {
                        val belowStart = centerY + clearance
                        drawLine(
                            color = connectorColor,
                            start = Offset(centerX, belowStart),
                            end = Offset(centerX, rowHeight),
                            strokeWidth = stroke,
                            pathEffect = PathEffect.dashPathEffect(intervals, rowTop + belowStart),
                        )
                    }
                },
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
            modifier = Modifier.weight(1f),
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
                    val prefix = if (isTrustAnchor) "Trust anchor, trusted via " else "Also trusted via "
                    TooltipBox(
                        tooltip = {
                            Tooltip {
                                Text(text = prefix + trustedVia.joinToString(", ") { trustSourceLabel(it) })
                            }
                        },
                        state = rememberTooltipState(),
                    ) {
                        Icon(
                            painter = painterResource(if (isTrustAnchor) Res.drawable.icon_anchor else Res.drawable.icon_check),
                            contentDescription = if (isTrustAnchor) "Trust anchor" else "Trusted",
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
                        Text(text = section.title, style = LumoTheme.typography.h4)
                        section.fields.forEach { field ->
                            CertificateFieldRow(label = field.label, value = field.value)
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
                ToastMessage(if (error == null) "Added to trusted certificates." else "Couldn't add: $error"),
            )
        }
    }

    TooltipBox(
        tooltip = { Tooltip { Text(text = "Add to trusted certificates") } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            variant = IconButtonVariant.Ghost,
            onClick = { if (adder.activeProfileName == null) commit(toActiveProfile = false) else expanded = true },
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_shield_plus),
                contentDescription = "Add to trusted certificates",
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
                        CertificateTrustMenuRow(label = "Global", onClick = { commit(toActiveProfile = false) })
                        adder.activeProfileName?.let { name ->
                            CertificateTrustMenuRow(
                                label = "Profile: $name",
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

/** Human-readable label for a [CertificateTrustSource], shown in the trusted-anchor tooltip. */
private fun trustSourceLabel(source: CertificateTrustSource): String = when (source) {
    is CertificateTrustSource.TrustedList -> source.name
    CertificateTrustSource.GlobalStore -> "Global trust store"
    is CertificateTrustSource.ProfileStore -> "Profile: ${source.profileName}"
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
