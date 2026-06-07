package cz.pizavo.omnisign.lumo.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Wraps [content] in a [SelectionContainer] so its text can be selected and copied to the
 * clipboard.
 *
 * Used for diagnostic, user-facing message text — errors, warnings, validation details — that
 * an operator may want to copy into a bug report, ticket or search. Keep interactive chrome
 * (buttons, toggles) out of the wrapped content; copy is worthless on a button label.
 *
 * This is the single seam for the app's "message text is selectable" behaviour: the rationale
 * lives here, and an explicit copy affordance could later be added in one place.
 *
 * @param modifier Optional modifier applied to the [SelectionContainer].
 * @param content The content to make selectable (typically a [Text] or an icon + text row).
 */
@Composable
fun SelectableContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SelectionContainer(modifier = modifier) {
        content()
    }
}
