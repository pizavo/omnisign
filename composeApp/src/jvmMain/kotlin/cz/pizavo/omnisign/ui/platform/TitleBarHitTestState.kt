package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Tracks the pixel-bounds of interactive controls rendered inside the custom
 * title bar so that JBR can distinguish click-targets from the draggable area.
 *
 * Compose toolbar buttons register their bounds via [updateRegion]. An AWT
 * [java.awt.event.MouseMotionListener] installed on the window then calls
 * [isOverControl] for every mouse event inside the title bar height; when the
 * cursor is over a registered control,
 * [com.jetbrains.WindowDecorations.CustomTitleBar.forceHitTest] is called with
 * `false` to let Compose handle the event instead of starting a window drag.
 */
class TitleBarHitTestState {

    private val regions = mutableMapOf<String, Rect>()

    /**
     * Register or update the pixel-bounds of a named control.
     *
     * Called from `Modifier.onGloballyPositioned` on each interactive element
     * inside the title bar (e.g., minimize button, theme toggle).
     *
     * @param key Stable identifier for the control (e.g. `"btn-minimize"`).
     * @param bounds Bounds in the window's coordinate space (pixels).
     */
    fun updateRegion(key: String, bounds: Rect) {
        regions[key] = bounds
    }

    /**
     * Returns `true` when the point ([x], [y]) in window coordinates falls
     * inside any registered control region.
     */
    fun isOverControl(x: Float, y: Float): Boolean =
        regions.values.any { it.contains(Offset(x, y)) }
}

