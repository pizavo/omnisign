package cz.pizavo.omnisign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.ui.platform.JbrTitleBarHelper
import cz.pizavo.omnisign.ui.platform.LocalTitleBarHeight
import cz.pizavo.omnisign.ui.platform.LocalTitleBarHitTest
import cz.pizavo.omnisign.ui.platform.LocalTitleBarRightInset
import cz.pizavo.omnisign.ui.platform.TitleBarHitTestState
import com.jetbrains.WindowDecorations
import org.koin.core.context.startKoin
import java.awt.AWTEvent
import java.awt.event.MouseEvent
import java.awt.Toolkit
import javax.swing.SwingUtilities

private const val TITLE_BAR_HEIGHT_DP = 40

/**
 * JVM desktop entry point.
 *
 * Launches a **decorated** [Window] with a JBR custom title bar — the OS handles
 * snapping, shadows, resize borders, and taskbar integration natively while
 * Compose renders its own toolbar in the title bar area. Native window-control
 * buttons (minimize, maximize, close) are provided by JBR; the toolbar leaves
 * space for them via [LocalTitleBarRightInset].
 *
 * The build toolchain guarantees JetBrains Runtime, so no non-JBR fallback is
 * needed.
 */
fun main() = application {
    startKoin {
        modules(
            appModule,
            jvmRepositoryModule,
        )
    }

    JbrDecoratedWindow(onCloseRequest = ::exitApplication)
}

/**
 * Decorated window backed by JBR's Custom Title Bar API.
 *
 * The OS keeps its native window frame (shadows, resize borders, snap assist)
 * but the title bar pixels are handed to Compose for custom rendering. A
 * toolkit-level [java.awt.event.AWTEventListener] checks every mouse motion
 * and press against registered control regions and calls
 * [com.jetbrains.WindowDecorations.CustomTitleBar.forceHitTest] to prevent
 * window drags over interactive buttons. The toolkit listener is used instead
 * of a per-component [java.awt.event.MouseMotionListener] so that events
 * dispatched to child components (e.g. the Compose rendering layer) are still
 * intercepted reliably.
 *
 * @param onCloseRequest Callback invoked when the window close is requested.
 */
@Composable
private fun ApplicationScope.JbrDecoratedWindow(onCloseRequest: () -> Unit) {
    val windowState = rememberWindowState()
    val hitTestState = remember { TitleBarHitTestState() }

    Window(
        onCloseRequest = onCloseRequest,
        undecorated = false,
        transparent = false,
        resizable = true,
        state = windowState,
        title = "OmniSign",
    ) {
        val awtWindow = window
        val titleBarHeightPx = TITLE_BAR_HEIGHT_DP.toFloat()

        val titleBar: WindowDecorations.CustomTitleBar? =
            remember { JbrTitleBarHelper.install(awtWindow, titleBarHeightPx) }

        DisposableEffect(titleBar) {
            if (titleBar == null) return@DisposableEffect onDispose {}

            val awtListener = java.awt.event.AWTEventListener { event ->
                if (event !is MouseEvent) return@AWTEventListener
                val source = event.component ?: return@AWTEventListener
                val ancestor = SwingUtilities.getWindowAncestor(source) ?: source
                if (ancestor !== awtWindow) return@AWTEventListener

                val point = SwingUtilities.convertPoint(source, event.point, awtWindow)
                if (point.y <= titleBarHeightPx &&
                    hitTestState.isOverControl(point.x.toFloat(), point.y.toFloat())
                ) {
                    titleBar.forceHitTest(false)
                }
            }

            Toolkit.getDefaultToolkit().addAWTEventListener(
                awtListener,
                AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK,
            )
            onDispose {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener)
            }
        }

        val rightInsetPx = titleBar?.rightInset ?: 0f

        CompositionLocalProvider(
            LocalTitleBarHeight provides TITLE_BAR_HEIGHT_DP.dp,
            LocalTitleBarHitTest provides { key, rect -> hitTestState.updateRegion(key, rect) },
            LocalTitleBarRightInset provides rightInsetPx,
        ) {
            App()
        }
    }
}
