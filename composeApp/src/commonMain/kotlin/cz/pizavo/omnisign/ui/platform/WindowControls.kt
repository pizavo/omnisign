package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Callbacks and state for native window operations on platforms that support
 * custom window decoration (JVM desktop with undecorated windows).
 *
 * Provided via [LocalWindowControls] from the platform entry point.
 * When `null` (e.g., on web), the toolbar hides its window-control buttons.
 *
 * @property onMinimize Minimizes the window to the taskbar/dock.
 * @property onMaximize Toggles between maximized and floating window placement.
 * @property onClose Requests application exit.
 * @property isMaximized Returns `true` when the window is currently maximized.
 */
data class WindowControls(
    val onMinimize: () -> Unit,
    val onMaximize: () -> Unit,
    val onClose: () -> Unit,
    val isMaximized: () -> Boolean,
)

/**
 * Provides [WindowControls] for the current platform.
 *
 * `null` on platforms that do not support custom window decoration (e.g., web).
 */
val LocalWindowControls = staticCompositionLocalOf<WindowControls?> { null }

/**
 * Modifier that makes a composable act as a window drag handle.
 *
 * On the JVM desktop this moves the undecorated window when dragged;
 * on other platforms this is a no-op identity modifier.
 */
val LocalWindowDragModifier = staticCompositionLocalOf<Modifier> { Modifier }

