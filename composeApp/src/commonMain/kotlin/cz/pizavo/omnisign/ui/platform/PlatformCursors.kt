package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Platform-specific [PointerIcon] representing a horizontal (left–right) resize cursor.
 *
 * On JVM desktop this maps to the native OS resize cursor (e.g. `IDC_SIZEWE` on Windows,
 * `NSCursor.resizeLeftRightCursor` on macOS) via the AWT cursor system that Compose Desktop
 * uses internally. On web, it maps to the CSS `col-resize` cursor.
 */
expect val HorizontalResizePointerIcon: PointerIcon

