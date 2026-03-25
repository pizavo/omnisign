package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * JVM desktop implementation of [HorizontalResizePointerIcon].
 *
 * Uses `Cursor.W_RESIZE_CURSOR` which AWT maps to the native OS resize cursor
 * (`NSCursor.resizeLeftRightCursor` on macOS, `IDC_SIZEWE` on Windows,
 * `XC_sb_h_double_arrow` on X11).  Compose Desktop's [PointerIcon] wraps
 * [java.awt.Cursor] internally, so this is the canonical approach.
 */
actual val HorizontalResizePointerIcon: PointerIcon =
    PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR))

