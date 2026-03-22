package cz.pizavo.omnisign

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.ui.platform.LocalWindowControls
import cz.pizavo.omnisign.ui.platform.LocalWindowDragModifier
import cz.pizavo.omnisign.ui.platform.WindowControls
import org.koin.core.context.startKoin

/**
 * JVM desktop entry point.
 *
 * Launches an undecorated [Window] styled after the IntelliJ "Island" theme.
 * Provides [WindowControls] and a drag-handle [Modifier] through composition
 * locals so the common [App] composable can render custom window chrome.
 */
fun main() = application {
    // Initialize Koin with all modules
    startKoin {
        modules(
            appModule,           // Common module from shared
            jvmRepositoryModule, // JVM repositories from shared
        )
    }

    val windowState = rememberWindowState()

    Window(
        onCloseRequest = ::exitApplication,
        undecorated = true,
        transparent = true,
        resizable = true,
        state = windowState,
        title = "OmniSign",
    ) {
        val awtWindow = window

        val windowControls = WindowControls(
            onMinimize = { windowState.isMinimized = true },
            onMaximize = {
                windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                    WindowPlacement.Floating else WindowPlacement.Maximized
            },
            onClose = ::exitApplication,
            isMaximized = { windowState.placement == WindowPlacement.Maximized },
        )

        val dragModifier = Modifier.pointerInput(Unit) {
            var startScreenPos = Offset.Zero
            var startWindowPos = IntOffset.Zero

            detectDragGestures(
                onDragStart = {
                    val mousePos = java.awt.MouseInfo.getPointerInfo().location
                    startScreenPos = Offset(mousePos.x.toFloat(), mousePos.y.toFloat())
                    startWindowPos = IntOffset(awtWindow.x, awtWindow.y)
                },
                onDrag = { change, _ ->
                    change.consume()
                    val mousePos = java.awt.MouseInfo.getPointerInfo().location
                    val current = Offset(mousePos.x.toFloat(), mousePos.y.toFloat())
                    awtWindow.setLocation(
                        (startWindowPos.x + (current.x - startScreenPos.x)).toInt(),
                        (startWindowPos.y + (current.y - startScreenPos.y)).toInt(),
                    )
                },
            )
        }

        CompositionLocalProvider(
            LocalWindowControls provides windowControls,
            LocalWindowDragModifier provides dragModifier,
        ) {
            App()
        }
    }
}

