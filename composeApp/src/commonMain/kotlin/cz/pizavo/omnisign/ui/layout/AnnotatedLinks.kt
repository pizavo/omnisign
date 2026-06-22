package cz.pizavo.omnisign.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import cz.pizavo.omnisign.lumo.LumoTheme

private val LINK_PLACEHOLDER = Regex("""\{(\d+)\}""")

/**
 * Build an [AnnotatedString] from a translatable [template] that carries positional placeholders
 * (`{1}`, `{2}`, …), inserting each entry of [links] at its placeholder.
 *
 * The 1-based placeholder index selects the link (`{1}` → first [links] entry). Each entry is a
 * `label to onClick` pair: when `onClick` is non-null the label is rendered as an underlined,
 * primary-colored clickable span; when `null` it is appended as plain text. Because the whole
 * sentence is a single [template], a translation controls the word order around the links — the
 * labels are inserted at the translator's chosen positions rather than concatenated in a fixed
 * order, which keeps the result grammatical in every language.
 *
 * @param template Resolved sentence containing a `{n}` placeholder for each link position.
 * @param links `label to onClick?` pairs, ordered to match `{1}`, `{2}`, …
 */
@Composable
fun annotatedWithLinks(
	template: String,
	vararg links: Pair<String, (() -> Unit)?>,
): AnnotatedString {
	val linkStyles = TextLinkStyles(
		style = SpanStyle(
			color = LumoTheme.colors.primary,
			textDecoration = TextDecoration.Underline,
		),
	)
	return buildAnnotatedString {
		var cursor = 0
		for (match in LINK_PLACEHOLDER.findAll(template)) {
			append(template.substring(cursor, match.range.first))
			val (label, onClick) = links.getOrElse(match.groupValues[1].toInt() - 1) { "" to null }
			if (onClick != null) {
				withLink(
					LinkAnnotation.Clickable(
						tag = "link",
						styles = linkStyles,
						linkInteractionListener = { onClick() },
					),
				) {
					append(label)
				}
			} else {
				append(label)
			}
			cursor = match.range.last + 1
		}
		append(template.substring(cursor))
	}
}
