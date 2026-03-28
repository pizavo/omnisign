package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Callback invoked by the toolbar drag spacer whenever its layout position or
 * size changes.
 *
 * On JVM desktop the implementation repositions a transparent AWT overlay
 * inside the [javax.swing.JLayeredPane] so that JBR's hit-test returns
 * `HTCAPTION` for the spacer region, giving the OS full native title-bar
 * behavior (drag with snap-assist, double-click maximize, right-click system
 * menu). On non-desktop platforms the default value is `null` and the spacer
 * falls back to [LocalWindowDragModifier].
 */
val LocalDragAreaCallback = staticCompositionLocalOf<((LayoutCoordinates) -> Unit)?> { null }

/**
 * [Modifier] that makes a composable behave as a window drag handle.
 *
 * On JVM desktop **with JBR** this is a no-op [Modifier] because window
 * dragging is handled natively via an AWT overlay positioned by
 * [LocalDragAreaCallback].
 *
 * On JVM desktop **without JBR** this falls back to Compose-level drag
 * gestures that call `Window.setLocation`, and double-tap to toggle
 * maximise / restore.
 *
 * On non-desktop platforms (e.g., Wasm) the default value is an empty
 * [Modifier] (no-op).
 */
val LocalWindowDragModifier = staticCompositionLocalOf<Modifier> { Modifier }

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

/**
 * Callback that informs the native title bar whether the application background
 * is dark so the OS can render window-control icons (minimize, maximize, close)
 * with an appropriate contrast color.
 *
 * On JVM desktop this sets the JBR `controls.dark` property on the
 * [com.jetbrains.WindowDecorations.CustomTitleBar]. On other platforms the
 * default value is `null` and callers skip the call.
 *
 * Pass `true` when the title bar area has a dark background (so the OS renders
 * light-colored icons) and `false` for a light background.
 */
val LocalTitleBarDarkControls = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }
