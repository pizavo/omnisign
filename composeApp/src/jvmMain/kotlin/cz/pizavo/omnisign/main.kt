package cz.pizavo.omnisign

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.platform.PasswordCallback
import cz.pizavo.omnisign.ui.platform.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Timer

/**
 * Resolved native log directory, set as a system property before Logback initialises.
 *
 * Top-level `val`s are initialized in declaration order, so placing this above the
 * [logger] property guarantees `omnisign.log.dir` is visible when SLF4J/Logback
 * reads `logback.xml`.
 */
private val LOG_DIR: String = resolveLogDir().also { System.setProperty("omnisign.log.dir", it) }

private val logger = KotlinLogging.logger {}

private const val TITLE_BAR_HEIGHT_DP = 40

/**
 * Interval in milliseconds for the [Timer] that primes
 * [WindowDecorations.CustomTitleBar.forceHitTest].
 */
private const val FORCE_HIT_TEST_POLL_MS = 8

/**
 * Resolves the platform-native log directory for the OmniSign desktop application.
 *
 * - **Windows**: `%LOCALAPPDATA%/omnisign/logs`
 * - **macOS**: `~/Library/Logs/omnisign`
 * - **Linux/other**: `$XDG_STATE_HOME/omnisign` (fallback `~/.local/state/omnisign`)
 */
private fun resolveLogDir(): String {
	val userHome = System.getProperty("user.home")
	val os = System.getProperty("os.name").lowercase()
	return when {
		os.contains("win") ->
			File(
				System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local",
				"omnisign/logs"
			).absolutePath

		os.contains("mac") ->
			File(userHome, "Library/Logs/omnisign").absolutePath

		else ->
			File(
				System.getenv("XDG_STATE_HOME") ?: "$userHome/.local/state",
				"omnisign"
			).absolutePath
	}
}

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
fun main() {
	application {
		startKoin {
			modules(
				appModule,
				jvmRepositoryModule,
				org.koin.dsl.module {
					single<PasswordCallback> { ComposePasswordCallback() }
				},
			)
		}

		logger.info { "OmniSign desktop started — log directory: $LOG_DIR" }

		JbrDecoratedWindow(onCloseRequest = ::exitApplication)
	}
}

/**
 * No-op [MouseAdapter] whose sole purpose is to exist on a component.
 *
 * JBR's custom title bar hit-test logic treats any AWT component that has a
 * mouse listener (or a custom cursor) as opaque — i.e. `HTCLIENT` rather than
 * `HTCAPTION`. Compose renders everything inside a single heavyweight
 * `ComposePanel` which already has its own Swing-level mouse listeners, so
 * the entire title bar zone is already `HTCLIENT` by default. This listener
 * is added as an explicit guarantee that the behavior stays consistent
 * regardless of internal Compose implementation changes.
 */
private val TitleBarClientAreaListener = object : MouseAdapter() {}

/**
 * Decorated window backed by JBR's Custom Title Bar API.
 *
 * The OS keeps its native window frame (shadows, resize borders, snap assist),
 * but the title bar pixels are handed to Compose for custom rendering.
 *
 * **Drag strategy** — JBR's hit-test considers any AWT component **without**
 * mouse listeners as "transparent" for native title bar actions (returns
 * `HTCAPTION`). Since Compose renders into a single `ComposePanel` that has
 * its own listeners, the entire title bar defaults to `HTCLIENT`
 * (non-draggable), making toolbar buttons work out of the box.
 *
 * To restore native dragging on the empty spacers between toolbar button
 * groups, a [Timer] running at ~120 Hz continuously polls the cursor
 * position. When the cursor is within **any** registered drag-area bounds
 * (synchronized from Compose via [LocalDragAreaCallback] using string keys)
 * the timer calls [WindowDecorations.CustomTitleBar.forceHitTest] with
 * `false`, which forces the **next** `WM_NCHITTEST` to return `HTCAPTION`.
 * Because both the timer and the Windows message pump execute on the AWT
 * EDT, the flag is reliably primed before the user's click is processed —
 * giving the OS full native title-bar behavior: drag with snap-assist,
 * double-click maximize / restore, right-click the system menu, and Aero Shake.
 *
 * When the cursor is stationary, the flag stays set indefinitely (no messages
 * consume it), so a click after hovering always triggers native drag.
 *
 * When JBR is unavailable a Compose-level fallback tracks drag gestures and
 * calls [java.awt.Window.setLocation]; double-tap toggles maximize / restore
 * via [Frame.extendedState].
 *
 * @param onCloseRequest Callback invoked when the window close is requested.
 */
@Composable
private fun JbrDecoratedWindow(onCloseRequest: () -> Unit) {
	val persisted = remember { WindowStateStore.load() }
	val windowState = rememberWindowState(
		placement = persisted?.placement ?: WindowPlacement.Floating,
		position = persisted?.let { WindowPosition.Absolute(it.x.dp, it.y.dp) }
			?: WindowPosition.PlatformDefault,
		size = persisted?.let { DpSize(it.width.dp, it.height.dp) }
			?: DpSize(800.dp, 600.dp),
	)
	
	var lastFloatingSize by remember { mutableStateOf(windowState.size) }
	var lastFloatingPosition by remember { mutableStateOf(windowState.position) }
	
	LaunchedEffect(Unit) {
		snapshotFlow { Triple(windowState.placement, windowState.size, windowState.position) }
			.collect { (placement, size, position) ->
				if (placement == WindowPlacement.Floating) {
					lastFloatingSize = size
					lastFloatingPosition = position
				}
			}
	}
	
	Window(
		onCloseRequest = {
			WindowStateStore.save(
				placement = windowState.placement,
				size = lastFloatingSize,
				position = lastFloatingPosition,
			)
			onCloseRequest()
		},
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
		
		remember(titleBar) {
			val contentPane = awtWindow.contentPane
			contentPane.addMouseListener(TitleBarClientAreaListener)
			contentPane.addMouseMotionListener(TitleBarClientAreaListener)
		}
		
		val hasTitleBar = titleBar != null
		
		val dragAreas = remember { ConcurrentHashMap<String, Rectangle>() }
		
		DisposableEffect(titleBar) {
			val timer = if (titleBar != null) {
				Timer(FORCE_HIT_TEST_POLL_MS) {
					val pointer = MouseInfo.getPointerInfo() ?: return@Timer
					val screen = pointer.location
					val panePos = try {
						awtWindow.contentPane.locationOnScreen
					} catch (_: Exception) {
						return@Timer
					}
					val localX = screen.x - panePos.x
					val localY = screen.y - panePos.y
					val hit = dragAreas.values.any { it.contains(localX, localY) }
					if (hit) {
						titleBar.forceHitTest(false)
					}
				}.apply { start() }
			} else null
			
			onDispose { timer?.stop() }
		}
		
		val awtScale = remember(awtWindow) {
			awtWindow.graphicsConfiguration.defaultTransform.scaleX
		}
		
		val dragAreaCallback: ((String, LayoutCoordinates) -> Unit)? = remember(hasTitleBar, dragAreas, awtScale) {
			if (!hasTitleBar) null
			else { key: String, coords: LayoutCoordinates ->
				val pos = coords.positionInWindow()
				val size = coords.size
				dragAreas[key] = Rectangle(
					(pos.x / awtScale).toInt(),
					(pos.y / awtScale).toInt(),
					(size.width / awtScale).toInt(),
					(size.height / awtScale).toInt(),
				)
			}
		}
		
		val windowDragModifier = remember(awtWindow, hasTitleBar) {
			if (hasTitleBar) Modifier else fallbackDragModifier(awtWindow)
		}
		
		val rightInsetPx = titleBar?.rightInset ?: 0f
		
		CompositionLocalProvider(
			LocalTitleBarHeight provides TITLE_BAR_HEIGHT_DP.dp,
			LocalTitleBarRightInset provides rightInsetPx,
			LocalWindowDragModifier provides windowDragModifier,
			LocalDragAreaCallback provides dragAreaCallback,
			LocalTitleBarDarkControls provides { isDark ->
				val tb = titleBar ?: return@provides
				tb.putProperty("controls.dark", isDark)
				try {
					JBR.getWindowDecorations()?.setCustomTitleBar(awtWindow, tb)
				} catch (_: Throwable) {
				}
			},
		) {
			App()
			
			val passwordCallback = remember {
				org.koin.mp.KoinPlatform.getKoinOrNull()
					?.getOrNull<PasswordCallback>() as? ComposePasswordCallback
			}
			val passwordRequest by (passwordCallback?.request
				?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }
			).collectAsState()
			
			passwordRequest?.let { request ->
				cz.pizavo.omnisign.ui.layout.PasswordDialog(
					title = request.title,
					prompt = request.prompt,
					onConfirm = { passwordCallback?.complete(it) },
					onCancel = { passwordCallback?.complete(null) },
				)
			}
		}
	}
}

/**
 * Creates a Compose-level drag [Modifier] used when JBR is unavailable.
 *
 * Drag gestures move the window via [java.awt.Window.setLocation]; double-tap
 * toggles maximize / restore via [Frame.extendedState].
 *
 * @param awtWindow The AWT frame to move.
 */
private fun fallbackDragModifier(awtWindow: Frame): Modifier =
	Modifier
		.pointerInput("double-tap") {
			detectTapGestures(
				onDoubleTap = {
					val maximised = awtWindow.extendedState and Frame.MAXIMIZED_BOTH != 0
					awtWindow.extendedState =
						if (maximised) Frame.NORMAL else Frame.MAXIMIZED_BOTH
				},
			)
		}
		.pointerInput("drag") {
			var startScreen = Point(0, 0)
			var startWindow = Point(0, 0)
			
			detectDragGestures(
				onDragStart = {
					startScreen = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
					startWindow = awtWindow.location
				},
				onDrag = { change, _ ->
					change.consume()
					val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
					awtWindow.setLocation(
						startWindow.x + (now.x - startScreen.x),
						startWindow.y + (now.y - startScreen.y),
					)
				},
			)
		}
