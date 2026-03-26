package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Callback for registering a named rectangular control region inside the custom
 * title bar so the OS does not initiate a window drag when the user interacts
 * with that area.
 *
 * On JVM desktop this feeds [TitleBarHitTestState]. On other platforms (web)
 * the default value is `null` and callers skip registration.
 */
val LocalTitleBarHitTest = staticCompositionLocalOf<((String, Rect) -> Unit)?> { null }

/**
 * Height of the custom title bar area in [Dp].
 *
 * On JVM desktop this matches the height set on the JBR
 * [com.jetbrains.WindowDecorations.CustomTitleBar] so that the Compose toolbar
 * and the native window-control buttons are always the same size.
 * Default is `40.dp`.
 */
val LocalTitleBarHeight = staticCompositionLocalOf<Dp> { 40.dp }

/**
 * Right-side inset in **AWT logical pixels** reserved for native window controls
 * (minimize, maximize, close) rendered by the JBR custom title bar.
 *
 * On Compose Desktop, AWT logical pixels correspond directly to dp, so the
 * toolbar converts this value with [Float.dp]. Default is `0f` (no inset).
 */
val LocalTitleBarRightInset = staticCompositionLocalOf { 0f }
