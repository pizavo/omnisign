package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.Surface
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.lumo.foundation.ripple
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_chevron_down
import org.jetbrains.compose.resources.painterResource

private val DropdownMenuShape = RoundedCornerShape(8.dp)
private val DropdownItemVerticalPadding = 10.dp
private val DropdownItemHorizontalPadding = 12.dp
private val DropdownMaxHeight = 260.dp

/**
 * Reusable dropdown selector rendered as a read-only [UnderlinedTextField] with a chevron-trailing
 * icon and a Lumo-styled popup menu.
 *
 * The popup uses Lumo [Surface] so it automatically inherits the correct surface
 * and text colors in both light and dark themes.
 *
 * A `null` selection is displayed as [nullLabel] (e.g. "Inherit from global").
 *
 * @param T The enum or item type.
 * @param selected The currently selected value, or `null`.
 * @param options All selectable values (excluding the null option which is always prepended).
 * @param onSelect Called with the newly selected value (or `null`).
 * @param label Optional composable label rendered above the field.
 * @param nullLabel Display text for the null / "inherit" option.
 * @param showNullOption When `false` the null / "inherit" row is hidden and only the
 *   concrete [options] are shown. Use this for fields that always require a value.
 * @param disabledOptions Items that should appear in the list but be shown as
 *   greyed-out and non-selectable (e.g., globally disabled algorithms).
 * @param itemLabel Lambda converting an item of type [T] to a display string used in the
 *   trigger field and for the null-option row. Always required even when [itemContent] is set.
 * @param itemContent Optional composable renderer for each concrete option row inside the
 *   popup. When provided it replaces the default single-line [Text] in each row, enabling
 *   rich layouts such as icons or multi-line content. [itemLabel] is still used for the
 *   trigger-field value display and the null / "inherit" row.
 * @param modifier Optional [Modifier] applied to the outer [Box].
 */
@Composable
fun <T> DropdownSelector(
    selected: T?,
    options: List<T>,
    onSelect: (T?) -> Unit,
    label: @Composable (() -> Unit)? = null,
    nullLabel: String = "Inherit from global",
    showNullOption: Boolean = true,
    disabledOptions: Set<T> = emptySet(),
    itemLabel: (T) -> String = { it.toString() },
    itemContent: (@Composable (T) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = if (selected != null) itemLabel(selected) else nullLabel
    val interactionSource = remember { MutableInteractionSource() }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                expanded = true
            }
        }
    }

    Box(modifier = modifier) {
        UnderlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords -> fieldSize = coords.size },
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
                offset = IntOffset(0, fieldSize.height),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                val widthDp = with(LocalDensity.current) { fieldSize.width.toDp() }
                DropdownMenuContent(
                    selected = selected,
                    options = options,
                    nullLabel = nullLabel,
                    showNullOption = showNullOption,
                    disabledOptions = disabledOptions,
                    itemLabel = itemLabel,
                    itemContent = itemContent,
                    menuWidth = widthDp,
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
 * Uses a Lumo [Surface] with the theme's surface color, outline border, and
 * elevation shadow so it integrates seamlessly with both light and dark themes.
 *
 * @param menuWidth The width the menu should occupy, matching the trigger field.
 * @param disabledOptions Items that appear greyed-out and cannot be selected.
 * @param itemContent Optional composable renderer that replaces the default [Text] in each
 *   concrete option row. When `null` [itemLabel] is used to produce a plain text row.
 */
@Composable
private fun <T> DropdownMenuContent(
    selected: T?,
    options: List<T>,
    nullLabel: String,
    showNullOption: Boolean,
    disabledOptions: Set<T>,
    itemLabel: (T) -> String,
    itemContent: (@Composable (T) -> Unit)?,
    menuWidth: androidx.compose.ui.unit.Dp,
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
                .width(menuWidth)
                .heightIn(max = DropdownMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            if (showNullOption) {
                DropdownItem(
                    text = nullLabel,
                    isSelected = selected == null,
                    secondary = true,
                    enabled = true,
                    onClick = { onSelect(null) },
                )
                HorizontalDivider()
            }
            options.forEach { item ->
                val isDisabled = item in disabledOptions
                DropdownItem(
                    text = itemLabel(item),
                    isSelected = item == selected,
                    secondary = false,
                    enabled = !isDisabled,
                    content = itemContent?.let { render -> { render(item) } },
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

/**
 * A single selectable row inside the dropdown popup.
 *
 * @param text Display label used when [content] is `null`.
 * @param isSelected Whether this option is currently selected (highlighted with primary color).
 * @param secondary When true, the text uses the secondary text color (for the null/"inherit" option).
 * @param enabled When false, the row is greyed-out and not clickable.
 * @param content Optional composable that replaces the default [Text] when provided. This composable
 * still applies the background, ripple, and padding; only the inner
 * visual content is delegated.
 * @param onClick Called when this row is clicked.
 */
@Composable
private fun DropdownItem(
    text: String,
    isSelected: Boolean,
    secondary: Boolean,
    enabled: Boolean,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected)
        LumoTheme.colors.primary.copy(alpha = 0.08f)
    else
        LumoTheme.colors.surface

    val textColor = when {
        !enabled -> LumoTheme.colors.textDisabled
        isSelected -> LumoTheme.colors.primary
        secondary -> LumoTheme.colors.textSecondary
        else -> LumoTheme.colors.text
    }

    Box(
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
            .padding(
                horizontal = DropdownItemHorizontalPadding,
                vertical = DropdownItemVerticalPadding,
            ),
    ) {
        if (content != null) {
            content()
        } else {
            Text(
                text = text,
                style = LumoTheme.typography.body2,
                color = textColor,
            )
        }
    }
}




