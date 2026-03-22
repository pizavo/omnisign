package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.card.Card
import cz.pizavo.omnisign.lumo.components.card.CardDefaults
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Animated side panel that slides in from a sidebar in the island layout.
 *
 * Wraps its content in a Lumo [Card] with rounded corners and provides a standard
 * header row containing the panel title and a close button. The body is scrollable.
 *
 * @param visible Whether the panel is currently expanded.
 * @param title Text displayed in the panel header.
 * @param onClose Callback invoked when the user clicks the close button.
 * @param fromEnd When `true` the panel slides in from the right edge; otherwise from the left.
 * @param modifier Optional [Modifier] applied to the [AnimatedVisibility] wrapper.
 * @param content Slot for the panel body, rendered inside a scrollable [Column].
 */
@Composable
fun IslandSidePanel(
    visible: Boolean,
    title: String,
    onClose: () -> Unit,
    fromEnd: Boolean = false,
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
        Card(
            modifier = Modifier
                .width(280.dp)
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
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = title, style = LumoTheme.typography.h3)

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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = content,
            )
        }
    }
}

