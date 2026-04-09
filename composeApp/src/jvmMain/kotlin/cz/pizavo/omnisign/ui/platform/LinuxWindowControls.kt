package cz.pizavo.omnisign.ui.platform

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.components.*
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import java.awt.Frame
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener

private val WindowControlButtonSize = 32.dp
private val WindowControlButtonPadding = PaddingValues(6.dp)

/**
 * Minimal close / minimize / maximize-restore button row for **undecorated Linux windows**.
 *
 * On Linux, JBR's [com.jetbrains.WindowDecorations] API is not supported, so the app
 * runs with `undecorated = true` (no native title bar). This composable is injected into
 * [cz.pizavo.omnisign.ui.layout.IslandToolbar] via [LocalWindowControls] and bridges
 * Compose to the underlying AWT [Frame].
 *
 * Window-state changes triggered by the window manager (e.g. maximise via the taskbar)
 * are tracked through a [WindowStateListener] attached for the lifetime of the composable.
 *
 * @param window The AWT frame whose state the buttons control.
 */
@Composable
fun LinuxWindowControls(window: Frame) {
    var isMaximized by remember { mutableStateOf(window.extendedState and Frame.MAXIMIZED_BOTH != 0) }

    DisposableEffect(window) {
        val listener = WindowStateListener { e ->
            isMaximized = e.newState and Frame.MAXIMIZED_BOTH != 0
        }
        window.addWindowStateListener(listener)
        onDispose { window.removeWindowStateListener(listener) }
    }

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            modifier = Modifier.defaultMinSize(
                minWidth = WindowControlButtonSize,
                minHeight = WindowControlButtonSize,
            ),
            variant = IconButtonVariant.Ghost,
            onClick = {
                window.extendedState = window.extendedState or Frame.ICONIFIED
            },
            contentPadding = WindowControlButtonPadding,
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_window_minimize),
                contentDescription = "Minimize",
                modifier = Modifier.size(18.dp),
            )
        }

        IconButton(
            modifier = Modifier.defaultMinSize(
                minWidth = WindowControlButtonSize,
                minHeight = WindowControlButtonSize,
            ),
            variant = IconButtonVariant.Ghost,
            onClick = {
                window.extendedState = if (isMaximized) Frame.NORMAL else Frame.MAXIMIZED_BOTH
                isMaximized = !isMaximized
            },
            contentPadding = WindowControlButtonPadding,
        ) {
            Icon(
                painter = painterResource(
                    if (isMaximized) Res.drawable.icon_window_restore
                    else Res.drawable.icon_window_maximize
                ),
                contentDescription = if (isMaximized) "Restore" else "Maximize",
                modifier = Modifier.size(18.dp),
            )
        }

        IconButton(
            modifier = Modifier.defaultMinSize(
                minWidth = WindowControlButtonSize,
                minHeight = WindowControlButtonSize,
            ),
            variant = IconButtonVariant.Ghost,
            onClick = {
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
            },
            contentPadding = WindowControlButtonPadding,
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_x),
                contentDescription = "Close",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

