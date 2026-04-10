package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Callback invoked by toolbar-drag spacers whenever their layout position or
 * size changes.
 *
 * Each spacer passes a unique [String] key and its [LayoutCoordinates].
 * On JVM desktop the implementation stores the bounds of every registered
 * drag area so that `CustomTitleBar.forceHitTest` is called when the cursor
 * is inside **any** of them — giving the OS full native title-bar behavior
 * (drag with snap-assist, double-click maximize, right-click system menu).
 * On non-desktop platforms the default value is `null` and the spacers fall
 * back to [LocalWindowDragModifier].
 */
val LocalDragAreaCallback = staticCompositionLocalOf<((String, LayoutCoordinates) -> Unit)?> { null }

/**
 * [Modifier] that makes a composable behave as a window drag handle.
 *
 * On JVM desktop **with JBR** this is a no-op [Modifier] because window
 * dragging is handled natively via an AWT overlay positioned by
 * [LocalDragAreaCallback].
 *
 * On JVM desktop **without JBR** this falls back to Compose-level drag
 * gestures that call `Window.setLocation`, and double-tap to toggle
 * maximizing / restore.
 *
 * On non-desktop platforms (e.g., Wasm) the default value is an empty
 * [Modifier] (no-op).
 */
val LocalWindowDragModifier = staticCompositionLocalOf<Modifier> { Modifier }

/**
 * Height of the custom title bar area in [Dp].
 *
 * On JVM desktop this matches the height set on the JBR
 * `CustomTitleBar` so that the Compose toolbar
 * and the native window-control buttons are always the same size.
 * Default is `40.dp`.
 */
val LocalTitleBarHeight = staticCompositionLocalOf { 40.dp }

/**
 * Right-side inset in **AWT logical pixels** reserved for native window controls
 * (minimize, maximize, close) rendered by the JBR custom title bar.
 *
 * On Compose Desktop, AWT logical pixels correspond directly to dp, so the
 * toolbar converts this value with [Float.dp]. Default is `0f` (no inset).
 */
val LocalTitleBarRightInset = staticCompositionLocalOf { 0f }

/**
 * Left-side inset in **AWT logical pixels** reserved for native window controls
 * rendered by the JBR custom title bar — on macOS these are the traffic-light
 * buttons (close / minimize / zoom). Toolbar content must not be placed behind
 * this region.
 *
 * On Compose Desktop, AWT logical pixels correspond directly to dp, so the
 * toolbar converts this value with [Float.dp]. Default is `0f` (no inset).
 */
val LocalTitleBarLeftInset = staticCompositionLocalOf { 0f }

/**
 * Whether the current host platform is macOS.
 *
 * Set once at startup from `System.getProperty("os.name")` on JVM desktop and
 * defaults to `false` on all other platforms (web, etc.). Used by toolbar
 * composables to apply platform-adaptive layout (e.g., logo placement) without
 * depending on transient inset values that change during fullscreen transitions.
 */
val LocalIsMacOs = staticCompositionLocalOf { false }

/**
 * Additional top padding in [Dp] that the toolbar must add above its interactive
 * content.
 *
 * On macOS full-screen this value is animated between `0.dp` and the height of
 * the OS auto-hiding title bar whenever the cursor approaches the top of the
 * window. Therefore, ensuring the toolbar's interactive controls remain accessible when the
 * system title bar slides into view. Outside of macOS full-screen the value is
 * always `0.dp`.
 */
val LocalTitleBarTopPadding = staticCompositionLocalOf { 0.dp }

/**
 * Callback that informs the native title bar whether the application background
 * is dark so the OS can render window-control icons (minimize, maximize, close)
 * with an appropriate contrast color.
 *
 * On JVM desktop this sets the JBR `controls.dark` property on the
 * `CustomTitleBar`. On other platforms the
 * default value is `null` and callers skip the call.
 *
 * Pass `true` when the title bar area has a dark background (so the OS renders
 * light-colored icons) and `false` for a light background.
 */
val LocalTitleBarDarkControls = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }
