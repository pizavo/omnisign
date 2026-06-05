package cz.pizavo.omnisign.lumo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import cz.pizavo.omnisign.lumo.components.Surface
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.foundation.ripple

/**
 * A [ContextMenuRepresentation] that renders the desktop text / selection context menu in the
 * app's Lumo design — a rounded [Surface] with an outline border, body2 items and ripple hover,
 * mirroring the app's DropdownSelector / ExportReportMenu popups.
 *
 * Colours and typography are read from [LumoTheme] at render time, so the menu tracks the active
 * light / dark theme. The menu opens at the cursor position carried by the open
 * [ContextMenuState.Status] via [rememberPopupPositionProviderAtPosition], which converts that
 * detector-local point to window coordinates and keeps the menu on-screen.
 */
class LumoContextMenuRepresentation : ContextMenuRepresentation {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return

        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = LumoTheme.colors.surface,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, LumoTheme.colors.outline),
            ) {
                Column(modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 180.dp).padding(vertical = 4.dp)) {
                    items().forEach { item ->
                        Text(
                            text = item.label,
                            style = LumoTheme.typography.body2,
                            color = LumoTheme.colors.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(),
                                    onClick = {
                                        state.status = ContextMenuState.Status.Closed
                                        item.onClick()
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
