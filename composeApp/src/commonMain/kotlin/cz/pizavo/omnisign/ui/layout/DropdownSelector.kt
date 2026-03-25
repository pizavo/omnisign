package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.Surface
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.textfield.TextField
import cz.pizavo.omnisign.lumo.foundation.ripple
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_chevron_down
import org.jetbrains.compose.resources.painterResource

private val DropdownMenuShape = RoundedCornerShape(8.dp)
private val DropdownItemVerticalPadding = 10.dp
private val DropdownItemHorizontalPadding = 12.dp
private val DropdownMaxHeight = 260.dp

/**
 * Reusable dropdown selector rendered as a read-only [TextField] with a chevron
 * trailing icon and a Lumo-styled popup menu.
 *
 * The popup uses Lumo [Surface] so it automatically inherits the correct surface
 * and text colours in both light and dark themes.
 *
 * A `null` selection is displayed as [nullLabel] (e.g. "Inherit from global").
 *
 * @param T The enum or item type.
 * @param selected The currently selected value, or `null`.
 * @param options All selectable values (excluding the null option which is always prepended).
 * @param onSelect Called with the newly selected value (or `null`).
 * @param label Optional composable label rendered above the field.
 * @param nullLabel Display text for the null / "inherit" option.
 * @param itemLabel Lambda converting an item of type [T] to a display string.
 * @param modifier Optional [Modifier] applied to the outer [Box].
 */
@Composable
fun <T> DropdownSelector(
    selected: T?,
    options: List<T>,
    onSelect: (T?) -> Unit,
    label: @Composable (() -> Unit)? = null,
    nullLabel: String = "Inherit from global",
    itemLabel: (T) -> String = { it.toString() },
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = if (selected != null) itemLabel(selected) else nullLabel
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                expanded = true
            }
        }
    }

    Box(modifier = modifier) {
        TextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            interactionSource = interactionSource,
            trailingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.icon_chevron_down),
                    contentDescription = "Expand",
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 0),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                DropdownMenuContent(
                    selected = selected,
                    options = options,
                    nullLabel = nullLabel,
                    itemLabel = itemLabel,
                    onSelect = { value ->
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Lumo-styled dropdown menu body rendered inside a [Popup].
 *
 * Uses a Lumo [Surface] with the theme's surface colour, outline border, and
 * elevation shadow so it integrates seamlessly with both light and dark themes.
 */
@Composable
private fun <T> DropdownMenuContent(
    selected: T?,
    options: List<T>,
    nullLabel: String,
    itemLabel: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    Surface(
        shape = DropdownMenuShape,
        color = LumoTheme.colors.surface,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, LumoTheme.colors.outline),
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .heightIn(max = DropdownMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            DropdownItem(
                text = nullLabel,
                isSelected = selected == null,
                secondary = true,
                onClick = { onSelect(null) },
            )
            HorizontalDivider()
            options.forEach { item ->
                DropdownItem(
                    text = itemLabel(item),
                    isSelected = item == selected,
                    secondary = false,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

/**
 * A single selectable row inside the dropdown popup.
 *
 * @param text Display label for this option.
 * @param isSelected Whether this option is currently selected (highlighted with primary colour).
 * @param secondary When true the text uses the secondary text colour (for the null/"inherit" option).
 * @param onClick Called when this row is clicked.
 */
@Composable
private fun DropdownItem(
    text: String,
    isSelected: Boolean,
    secondary: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected)
        LumoTheme.colors.primary.copy(alpha = 0.08f)
    else
        LumoTheme.colors.surface

    val textColor = when {
        isSelected -> LumoTheme.colors.primary
        secondary -> LumoTheme.colors.textSecondary
        else -> LumoTheme.colors.text
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(
                horizontal = DropdownItemHorizontalPadding,
                vertical = DropdownItemVerticalPadding,
            ),
    ) {
        Text(
            text = text,
            style = LumoTheme.typography.body2,
            color = textColor,
        )
    }
}




