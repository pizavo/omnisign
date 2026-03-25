package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword

/**
 * Wasm/JS implementation of [HorizontalResizePointerIcon].
 *
 * Uses the CSS `col-resize` cursor keyword which renders the browser's native
 * bidirectional horizontal resize cursor.
 *
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/CSS/cursor">MDN – cursor</a>
 */
@OptIn(ExperimentalComposeUiApi::class)
actual val HorizontalResizePointerIcon: PointerIcon =
    PointerIcon.fromKeyword("col-resize")

