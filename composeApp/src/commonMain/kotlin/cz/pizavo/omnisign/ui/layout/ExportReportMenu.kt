package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cz.pizavo.omnisign.domain.model.validation.ReportExportFormat
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Surface
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.lumo.foundation.ripple
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_download
import org.jetbrains.compose.resources.painterResource

private val MenuShape = RoundedCornerShape(8.dp)
private val MenuMaxHeight = 360.dp
private val MenuWidth = 340.dp

/**
 * An icon button that opens a popup menu listing available [ReportExportFormat] options.
 *
 * Each menu item shows the format [label][ReportExportFormat.label] and its
 * [description][ReportExportFormat.description]. Selecting an item invokes
 * [onFormatSelected] and closes the popup.
 *
 * @param availableFormats Formats that the user can choose from. Formats not in this
 *   list are greyed out (they require raw DSS data not present in the report).
 * @param onFormatSelected Callback invoked with the chosen format.
 * @param modifier Optional [Modifier] applied to the outer wrapper.
 */
@Composable
fun ExportReportMenu(
    availableFormats: List<ReportExportFormat>,
    onFormatSelected: (ReportExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    TooltipBox(
        tooltip = { Tooltip { Text(text = "Export report") } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(
            variant = IconButtonVariant.Ghost,
            onClick = { expanded = true },
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_download),
                contentDescription = "Export validation report",
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                ExportMenuContent(
                    availableFormats = availableFormats,
                    onSelect = { format ->
                        expanded = false
                        onFormatSelected(format)
                    },
                )
            }
        }
    }
}

/**
 * Popup body listing all [ReportExportFormat] entries with label and description.
 */
@Composable
private fun ExportMenuContent(
    availableFormats: List<ReportExportFormat>,
    onSelect: (ReportExportFormat) -> Unit,
) {
    Surface(
        shape = MenuShape,
        color = LumoTheme.colors.surface,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, LumoTheme.colors.outline),
    ) {
        Column(
            modifier = Modifier
                .width(MenuWidth)
                .heightIn(max = MenuMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Export format",
                    style = LumoTheme.typography.label1,
                    color = LumoTheme.colors.textSecondary,
                )
            }
            HorizontalDivider()
            ReportExportFormat.entries.forEach { format ->
                val enabled = format in availableFormats
                ExportMenuItem(
                    format = format,
                    enabled = enabled,
                    onClick = { onSelect(format) },
                )
            }
        }
    }
}

/**
 * A single selectable row inside the export-format popup.
 *
 * @param format The export format this row represents.
 * @param enabled Whether the format can currently be selected.
 * @param onClick Called when the row is clicked (only when [enabled]).
 */
@Composable
private fun ExportMenuItem(
    format: ReportExportFormat,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = LumoTheme.colors.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = format.label,
                style = LumoTheme.typography.body2,
                color = if (enabled) LumoTheme.colors.text else LumoTheme.colors.textDisabled,
            )
            Text(
                text = format.description,
                style = LumoTheme.typography.body3,
                color = if (enabled) LumoTheme.colors.textSecondary else LumoTheme.colors.textDisabled,
            )
        }
        Text(
            text = ".${format.extension}",
            style = LumoTheme.typography.body3,
            color = if (enabled) LumoTheme.colors.textSecondary else LumoTheme.colors.textDisabled,
        )
    }
}



