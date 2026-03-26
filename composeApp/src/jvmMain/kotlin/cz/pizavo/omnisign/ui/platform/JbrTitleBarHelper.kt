package cz.pizavo.omnisign.ui.platform

import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Frame

/**
 * Encapsulates JetBrains Runtime (JBR) Custom Title Bar setup.
 *
 * Removes the native title bar while keeping the window **decorated** —
 * preserving OS-level window management such as edge snapping, shadows,
 * resize borders, and taskbar integration. The freed title bar area is
 * handed to Compose for custom toolbar rendering.
 */
object JbrTitleBarHelper {

    /**
     * Creates and installs a [WindowDecorations.CustomTitleBar] on [window].
     *
     * After this call the native title bar is hidden and the top [height]
     * pixels of the window's client area are treated as the title bar by the
     * OS (draggable, double-click to maximize, etc.).
     *
     * @param window The AWT frame to customize.
     * @param height Title bar height in physical pixels.
     * @return The installed [WindowDecorations.CustomTitleBar], or `null` when
     *   JBR is not available.
     */
    fun install(window: Frame, height: Float): WindowDecorations.CustomTitleBar? = try {
        val decorations = JBR.getWindowDecorations() ?: return null
        val titleBar = decorations.createCustomTitleBar()
        titleBar.height = height
        decorations.setCustomTitleBar(window, titleBar)
        titleBar
    } catch (_: Throwable) {
        null
    }
}
