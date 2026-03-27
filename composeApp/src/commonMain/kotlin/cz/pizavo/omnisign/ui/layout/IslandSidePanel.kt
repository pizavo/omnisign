package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.card.Card
import cz.pizavo.omnisign.lumo.components.card.CardDefaults
import cz.pizavo.omnisign.ui.platform.HorizontalResizePointerIcon
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_arrow_left
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/** Default width of a side panel when first opened. */
val IslandSidePanelDefaultWidth = 280.dp

/** Minimum width a side panel can be resized to. */
val IslandSidePanelMinWidth = 200.dp

private val ResizeHandleWidth = 6.dp

/**
 * Animated side panel that slides in from a sidebar in the island layout.
 *
 * Wraps its content in a Lumo [Card] with rounded corners and provides a standard
 * header row containing the panel title and a close button. When [onBack] is supplied
 * a back-arrow button is rendered before the title for drill-down navigation.
 * The body is scrollable. The panel is horizontally resizable via a drag handle
 * on its inner edge.
 *
 * @param visible Whether the panel is currently expanded.
 * @param title Text displayed in the panel header.
 * @param onClose Callback invoked when the user clicks the close button.
 * @param panelWidth Current width of the panel.
 * @param maxPanelWidth Maximum width the panel can be resized to.
 * @param onWidthChange Callback invoked with the new width when the user drags the resize handle.
 * @param fromEnd When `true` the panel slides in from the right edge; otherwise from the left.
 * @param onBack Optional callback for back navigation; when non-null, a back-arrow icon is shown.
 * @param headerActions Optional composable slot rendered in the header row between the title and the
 *   close button. Use it for action icons such as export or refresh.
 * @param modifier Optional [Modifier] applied to the [AnimatedVisibility] wrapper.
 * @param content Slot for the panel body, rendered inside a scrollable [Column].
 */
@Composable
fun IslandSidePanel(
    visible: Boolean,
    title: String,
    onClose: () -> Unit,
    panelWidth: Dp = IslandSidePanelDefaultWidth,
    maxPanelWidth: Dp = panelWidth,
    onWidthChange: (Dp) -> Unit = {},
    fromEnd: Boolean = false,
    onBack: (() -> Unit)? = null,
    headerActions: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val direction = if (fromEnd) 1 else -1
    val animDuration = 250

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(
            animationSpec = tween(animDuration),
            initialOffsetX = { fullWidth -> direction * fullWidth },
        ) + fadeIn(animationSpec = tween(animDuration)),
        exit = slideOutHorizontally(
            animationSpec = tween(animDuration),
            targetOffsetX = { fullWidth -> direction * fullWidth },
        ) + fadeOut(animationSpec = tween(animDuration)),
    ) {
        Row(
            modifier = Modifier
                .width(panelWidth)
                .fillMaxHeight(),
        ) {
            if (fromEnd) {
                ResizeHandle(
                    panelWidth = panelWidth,
                    maxPanelWidth = maxPanelWidth,
                    onWidthChange = onWidthChange,
                    fromEnd = true,
                )
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                    draggedElevation = 0.dp,
                    disabledElevation = 0.dp,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) {
                            IconButton(
                                variant = IconButtonVariant.Ghost,
                                onClick = onBack,
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.icon_arrow_left),
                                    contentDescription = "Back",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(text = title, style = LumoTheme.typography.h3)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        headerActions?.invoke()

                        IconButton(
                            variant = IconButtonVariant.Ghost,
                            onClick = onClose,
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.icon_x),
                                contentDescription = "Close panel",
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    content = content,
                )
            }

            if (!fromEnd) {
                ResizeHandle(
                    panelWidth = panelWidth,
                    maxPanelWidth = maxPanelWidth,
                    onWidthChange = onWidthChange,
                    fromEnd = false,
                )
            }
        }
    }
}

/**
 * Thin vertical drag handle rendered on the inner edge of an [IslandSidePanel].
 *
 * Dragging horizontally changes the panel width via [onWidthChange]. The handle
 * displays a horizontal-resize cursor on hover and shows a subtle highlight
 * colour when the pointer is over it. Double-tapping resets the panel to its
 * default width.
 *
 * @param panelWidth Current width of the owning panel.
 * @param maxPanelWidth Maximum width the panel can be resized to.
 * @param onWidthChange Callback that receives the new desired width during a drag gesture.
 * @param fromEnd When `true` the handle belongs to a right-side panel (the drag direction is inverted).
 */
@Composable
private fun ResizeHandle(
    panelWidth: Dp,
    maxPanelWidth: Dp,
    onWidthChange: (Dp) -> Unit,
    fromEnd: Boolean,
) {
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val currentWidth by rememberUpdatedState(panelWidth)
    val currentMaxWidth by rememberUpdatedState(maxPanelWidth)
    val currentOnWidthChange by rememberUpdatedState(onWidthChange)

    Box(
        modifier = Modifier
            .width(ResizeHandleWidth)
            .fillMaxHeight()
            .hoverable(interactionSource)
            .pointerHoverIcon(HorizontalResizePointerIcon)
            .background(
                if (isHovered) LumoTheme.colors.textSecondary.copy(alpha = 0.15f)
                else LumoTheme.colors.textSecondary.copy(alpha = 0f),
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        currentOnWidthChange(
                            IslandSidePanelDefaultWidth.coerceIn(IslandSidePanelMinWidth, currentMaxWidth)
                        )
                    },
                )
            }
            .pointerInput(fromEnd) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaDp = with(density) { dragAmount.x.toDp() }
                    val newWidth = if (fromEnd) currentWidth - deltaDp else currentWidth + deltaDp
                    currentOnWidthChange(newWidth.coerceIn(IslandSidePanelMinWidth, currentMaxWidth))
                }
            },
    )
}
