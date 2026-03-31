package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState

/**
 * Renders a superscript "?" that reveals a [Tooltip] with the given [text] on hover.
 *
 * Use this next to a label when extra context (e.g., the resulting PAdES level) should
 * be available on demand without cluttering the label itself.
 *
 * @param text Tooltip content shown on hover.
 * @param modifier Optional modifier applied to the tooltip anchor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoTooltip(
	text: String,
	modifier: Modifier = Modifier,
) {
	TooltipBox(
		tooltip = { Tooltip { Text(text = text) } },
		state = rememberTooltipState(),
		modifier = modifier,
	) {
		Text(
			text = buildAnnotatedString {
				withStyle(
					SpanStyle(
						fontSize = 9.sp,
						baselineShift = BaselineShift.Superscript,
						color = LumoTheme.colors.primary,
					),
				) {
					append("?")
				}
			},
			style = LumoTheme.typography.body2,
		)
	}
}

