package cz.pizavo.omnisign

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import com.jetbrains.WindowMove
import cz.pizavo.omnisign.data.service.NotificationUrgency
import cz.pizavo.omnisign.data.service.OsNotificationService
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import cz.pizavo.omnisign.ui.platform.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Timer
import kotlin.system.exitProcess

/**
 * Resolved native log directory, set as a system property before Logback initializes.
 *
 * Top-level `val`s are initialized in declaration order, so placing this above the
 * [logger] property guarantees `omnisign.log.dir` is visible when SLF4J/Logback
 * reads `logback.xml`.
 */
private val LOG_DIR: String = resolveLogDir().also { System.setProperty("omnisign.log.dir", it) }

private val logger = KotlinLogging.logger {}

private const val TITLE_BAR_HEIGHT_DP = 40

/**
 * Height in dp of the macOS system auto-hide title bar that slides down from
 * the top of the screen when the window is in native full-screen mode.
 *
 * Used as the animation target for [LocalTitleBarTopPadding] and as the
 * hysteresis close-threshold for the cursor-tracking timer: the gap is kept
 * open as long as the cursor is within this many logical pixels of the window
 * top, collapsing only once the cursor moves clearly below it.
 */
private const val MAC_FULLSCREEN_TOP_INSET_DP = 28

/**
 * Cursor Y-position threshold (in logical pixels, relative to the window top)
 * at which the macOS full-screen gap is opened.
 *
 * A value of `2` means the gap appears only when the cursor is at the very top
 * edge of the screen — the same moment macOS shows its auto-hiding title bar —
 * rather than opening prematurely across the entire toolbar height. Negative
 * values (cursor above the window, inside the native title bar area) also
 * satisfy this condition, so the gap stays open while the user interacts with
 * the system title bar.
 */
private const val MAC_FULLSCREEN_TRIGGER_DP = 2

/**
 * Polling interval in milliseconds for the macOS full-screen cursor-tracking
 * [Timer].
 *
 * At 16 ms (~60 Hz) the gap responds within one frame of the cursor reaching
 * the top edge, giving a smooth transition that matches the native title bar
 * slide-in animation.
 */
private const val MAC_FULLSCREEN_CURSOR_POLL_MS = 16

/**
 * Interval in milliseconds for the [Timer] that primes
 * [WindowDecorations.CustomTitleBar.forceHitTest].
 */
private const val FORCE_HIT_TEST_POLL_MS = 8

/**
 * `true` when the app is running on Linux (or another non-Windows, non-macOS OS).
 *
 * On Linux the window defaults to **undecorated** CSD mode so the Compose toolbar
 * can occupy the full top edge (the WM's native title bar is removed). Users may
 * opt into the native title bar via Settings > Appearance > Window, in which case
 * the window is created decorated and CSD drag / controls are skipped — see
 * [isLinuxCsd].
 *
 * Unlike Windows and macOS, JBR's Custom Title Bar on X11 does **not** provide
 * native window-control buttons or working `forceHitTest` drag on an undecorated
 * frame, so [LinuxWindowControls] supplies minimize / maximize / close and
 * [JBR.getWindowMove] is used for WM-level drag with edge-snapping support.
 */
private val isLinux: Boolean = System.getProperty("os.name").lowercase().let {
	!it.contains("win") && !it.contains("mac")
}

/**
 * User preference for using the native (decorated) title bar on Linux instead of
 * the custom merged toolbar.
 *
 * When `true`, the window is created with `undecorated = false` on Linux and the
 * toolbar is rendered below the native title bar. When `false` (default), the window
 * is undecorated and the Compose toolbar acts as a CSD header with custom window
 * controls and WM-assisted drag.
 *
 * The preference is loaded once at startup from `~/.config/omnisign/appearance.properties`
 * and can be changed via the Settings dialog under Appearance > Window (requires restart).
 */
private val useNativeTitleBar: Boolean = if (isLinux) loadUseNativeTitleBar() ?: false else false

/**
 * Whether the Linux CSD mode is active: the window is undecorated and the Compose
 * toolbar acts as a custom title bar with window controls and WM-assisted drag.
 *
 * `true` when running on Linux and the user has **not** opted into the native title bar.
 */
private val isLinuxCsd: Boolean = isLinux && !useNativeTitleBar

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
 * Launches a [Window] with a JBR custom title bar — the OS handles snapping,
 * shadows, resize borders, and taskbar integration natively while Compose
 * renders its own toolbar in the title bar area. Native window-control buttons
 * (minimize, maximize, close) are provided by JBR on Windows and macOS; on
 * Linux the window is **undecorated** and [LinuxWindowControls] provides
 * custom buttons while [WindowMove.startMovingTogetherWithMouse] handles
 * WM-level drag with edge-snapping.
 *
 * The build toolchain guarantees JetBrains Runtime.
 *
 * When invoked with the `renew` argument (e.g., by the OS scheduler), the app
 * runs the renewal batch in headless mode and exits without starting the GUI.
 */
fun main(args: Array<String> = emptyArray()) {
	System.setProperty("sun.awt.wmclass", "OmniSign")

	if (args.isNotEmpty() && args[0] == "renew") {
		runHeadlessRenewal()
		return
	}

	// On Linux, JBR 25 sets WM_CLASS from XToolkit.awtAppClassName, which is derived
	// from the call-stack's bottom frame (the main class name) in the XToolkit constructor.
	// It no longer reads sun.awt.wmclass for this purpose.  We force toolkit
	// initialization, then override the private static field via reflection so that
	// every subsequent X11 window gets WM_CLASS = "OmniSign".
	// --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED must be in jvmArgs (see build.gradle.kts).
	// This block must come AFTER the renew-mode early-exit: Toolkit.getDefaultToolkit()
	// starts the AWT event-dispatch thread (a non-daemon thread), which would prevent
	// the JVM from exiting cleanly after a headless-renewal run.
	val displayServer: DisplayServer? = if (isLinux) {
		val ds = DisplayServer.detect()
		if (ds != DisplayServer.WAYLAND) {
			try {
				java.awt.Toolkit.getDefaultToolkit() // ensure XToolkit.<init> has already run
				val cls = Class.forName("sun.awt.X11.XToolkit")
				val fld = cls.getDeclaredField("awtAppClassName")
				fld.isAccessible = true
				fld.set(null, "OmniSign")
			} catch (_: Exception) {
				// Non-X11 display or restricted JVM – ignore.
			}
		}
		ds
	} else null

	application {
		startKoin {
			modules(
				appModule,
				jvmRepositoryModule,
				org.koin.dsl.module {
					single { ComposePasswordCallback() }
					single<PasswordCallback> { get<ComposePasswordCallback>() }
					single<PasswordDialogController> { get<ComposePasswordCallback>() }
					single { WindowStateStore() }
				},
			)
		}

		logger.info { "OmniSign desktop started — log directory: $LOG_DIR" }

		if (isLinux) {
			logger.info {
				"Linux: displayServer=${displayServer?.label}, " +
						"useNativeTitleBar=$useNativeTitleBar, isLinuxCsd=$isLinuxCsd"
			}
			if (displayServer == DisplayServer.WAYLAND) {
				logger.warn {
					"Native Wayland session detected — WM-assisted drag (_NET_WM_MOVERESIZE) is " +
							"unavailable. Edge-snapping may not work. Consider launching with " +
							"-Dawt.toolkit.name=XToolkit to force XWayland for full functionality."
				}
			}
		}

		JbrDecoratedWindow(onCloseRequest = ::exitApplication)
	}
}

/**
 * Headless renewal mode invoked by the OS scheduler.
 *
 * Bootstraps Koin with a no-op [PasswordCallback] (renewal never needs
 * interactive authentication), runs all configured renewal jobs via
 * [RenewBatchUseCase], sends OS notifications for completed jobs, and
 * exits with code 0 on success or 1 if any errors occurred.
 */
private fun runHeadlessRenewal() {
	val koinApp = startKoin {
		modules(
			appModule,
			jvmRepositoryModule,
			org.koin.dsl.module {
				single<PasswordCallback> {
					object : PasswordCallback {
						override fun requestPassword(prompt: String, title: String): String? = null
					}
				}
			},
		)
	}

	logger.info { "OmniSign headless renewal started — log directory: $LOG_DIR" }

	val koin = koinApp.koin
	val renewBatch = koin.get<RenewBatchUseCase>()
	val notificationService = koin.get<OsNotificationService>()

	val result = runBlocking { renewBatch() }
	stopKoin()

	if (result == null || result.jobs.isEmpty()) {
		logger.info { "No renewal jobs configured — exiting." }
		exitProcess(0)
	}

	logger.info {
		"Renewal complete: checked=${result.checked}, renewed=${result.renewed}, " +
				"skipped=${result.skipped}, errors=${result.errors}"
	}

	for (job in result.jobs) {
		if (!job.notify) continue
		when {
			job.errors > 0 && job.renewed > 0 -> notificationService.notify(
				title = "OmniSign — Renewal partial failure (${job.name})",
				body = "${job.renewed} file(s) re-timestamped, ${job.errors} error(s). Check the log for details.",
				urgency = NotificationUrgency.CRITICAL,
			)

			job.errors > 0 -> notificationService.notify(
				title = "OmniSign — Renewal failed (${job.name})",
				body = "${job.errors} file(s) could not be re-timestamped. Digital continuity may be at risk.",
				urgency = NotificationUrgency.CRITICAL,
			)

			job.renewed > 0 -> notificationService.notify(
				title = "OmniSign — Renewal complete (${job.name})",
				body = "${job.renewed} file(s) successfully re-timestamped.",
				urgency = NotificationUrgency.NORMAL,
			)
		}
	}

	exitProcess(if (result.success) 0 else 1)
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
 * Window backed by JBR's Custom Title Bar API.
 *
 * On **Windows and macOS** the window is **decorated**: the OS keeps its native
 * frame (shadows, resize borders, snap assist) while JBR hides the title bar
 * and hands its pixels to Compose. The OS provides native window-control buttons *  at `rightInset` / `leftInset`. Drag is handled via
 * [WindowDecorations.CustomTitleBar.forceHitTest] on a high-frequency polling
 * timer that primes the next hit-test to return `HTCAPTION` when the cursor
 * is over a registered drag area.
 *
 * On **Linux** the window is **undecorated**, so the Compose toolbar can occupy
 * the full top edge. JBR's `forceHitTest` does not work on X11, and the WM
 * does not render native buttons on undecorated frames. Instead:
 * - [LinuxWindowControls] provides minimize / maximize / close buttons.
 * - [WindowMove.startMovingTogetherWithMouse] initiates WM-level moves
 *   (via `_NET_WM_MOVERESIZE`), giving native drag with edge-snapping.
 * - Double-click on drag areas toggles maximize / restore.
 *
 * @param onCloseRequest Callback invoked when the window close is requested.
 */
@Composable
private fun JbrDecoratedWindow(onCloseRequest: () -> Unit) {
	val windowStateStore = remember { org.koin.mp.KoinPlatform.getKoin().get<WindowStateStore>() }
	val persisted = remember { windowStateStore.load() }
	val windowState = rememberWindowState(
		placement = persisted?.placement ?: WindowPlacement.Floating,
		position = persisted?.let { WindowPosition.Absolute(it.x.dp, it.y.dp) }
			?: WindowPosition.PlatformDefault,
		size = persisted?.let { DpSize(it.width.dp, it.height.dp) }
			?: DpSize(800.dp, 600.dp),
	)
	
	var lastFloatingSize by remember { mutableStateOf(windowState.size) }
	var lastFloatingPosition by remember { mutableStateOf(windowState.position) }

	val windowIcon = remember {
		Thread.currentThread().contextClassLoader
			.getResourceAsStream("omnisign-logo.png")
			?.buffered()
			?.use { it.readAllBytes().decodeToImageBitmap() }
	}

	val windowIconPainter = remember(windowIcon) { windowIcon?.let { BitmapPainter(it) } }
	
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
			windowStateStore.save(
				placement = windowState.placement,
				size = lastFloatingSize,
				position = lastFloatingPosition,
			)
			onCloseRequest()
		},
		icon = windowIconPainter,
		undecorated = isLinuxCsd,
		transparent = false,
		resizable = true,
		state = windowState,
		title = "OmniSign",
	) {
		val awtWindow = window

		remember(windowIcon) {
			windowIcon?.toAwtImage()?.let { awtWindow.iconImage = it }
		}

		if (isLinuxCsd) {
			remember(awtWindow) {
				try {
					val xWindow = Class.forName("sun.awt.X11.XToolkit")
						.getMethod("windowToXWindow", java.awt.Window::class.java)
						.invoke(null, awtWindow)
					val xAtomClass = Class.forName("sun.awt.X11.XAtom")
					val getMethod = xAtomClass.getMethod("get", String::class.java)
					val typeAtom = getMethod.invoke(null, "_NET_WM_WINDOW_TYPE")
					val normalAtom = getMethod.invoke(null, "_NET_WM_WINDOW_TYPE_NORMAL")
					val windowId = xWindow.javaClass.getMethod("getWindow").invoke(xWindow) as Long
					val atomValue = xAtomClass.getMethod("getAtom").invoke(normalAtom) as Long
					val setAtomListPropertyMethod = typeAtom.javaClass.getMethod(
						"setAtomListProperty", Long::class.javaPrimitiveType, LongArray::class.java,
					)
					setAtomListPropertyMethod.invoke(typeAtom, windowId, longArrayOf(atomValue))
				} catch (_: Throwable) {
					// Non-X11 session or restricted JVM — WM shadows may not appear.
				}
			}
		}

		val isMacOs = remember { System.getProperty("os.name").lowercase().contains("mac") }
		val isFullscreen = windowState.placement == WindowPlacement.Fullscreen

		val titleBar: WindowDecorations.CustomTitleBar? =
			remember { JbrTitleBarHelper.install(awtWindow, TITLE_BAR_HEIGHT_DP.toFloat()) }

		val isMacTitleBarHoveringState = remember { mutableStateOf(false) }
		val isMacTitleBarHovering by isMacTitleBarHoveringState

		DisposableEffect(isMacOs, isFullscreen, awtWindow) {
			if (!isMacOs || !isFullscreen) {
				isMacTitleBarHoveringState.value = false
				return@DisposableEffect onDispose {}
			}
			val timer = Timer(MAC_FULLSCREEN_CURSOR_POLL_MS) {
				val pointer = MouseInfo.getPointerInfo() ?: return@Timer
				val windowTop = try {
					awtWindow.locationOnScreen.y
				} catch (_: Exception) {
					return@Timer
				}
				val cursorWindowY = pointer.location.y - windowTop
				val current = isMacTitleBarHoveringState.value
				val next = when {
					cursorWindowY <= MAC_FULLSCREEN_TRIGGER_DP -> true
					cursorWindowY > MAC_FULLSCREEN_TOP_INSET_DP -> false
					else -> current
				}
				if (current != next) isMacTitleBarHoveringState.value = next
			}
			timer.start()
			onDispose {
				timer.stop()
				isMacTitleBarHoveringState.value = false
			}
		}

		val macTitleBarPaddingDp by animateDpAsState(
			targetValue = if (isMacOs && isFullscreen && isMacTitleBarHovering) MAC_FULLSCREEN_TOP_INSET_DP.dp else 0.dp,
			animationSpec = tween(durationMillis = 200),
			label = "macTitleBarPadding",
		)

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
			when {
				isLinuxCsd -> linuxWmDragModifier(awtWindow)
				hasTitleBar -> Modifier
				else -> fallbackDragModifier(awtWindow)
			}
		}

		val rightInsetPx = remember(isFullscreen, titleBar) { titleBar?.rightInset ?: 0f }
		val leftInsetPx = remember(isFullscreen, titleBar) { titleBar?.leftInset ?: 0f }
		
		val windowControlsContent: (@Composable () -> Unit)? = if (isLinuxCsd) {
			{ LinuxWindowControls(awtWindow) }
		} else null
		
		CompositionLocalProvider(
			LocalTitleBarHeight provides TITLE_BAR_HEIGHT_DP.dp + macTitleBarPaddingDp,
			LocalTitleBarRightInset provides rightInsetPx,
			LocalTitleBarLeftInset provides leftInsetPx,
			LocalTitleBarTopPadding provides macTitleBarPaddingDp,
			LocalIsMacOs provides isMacOs,
			LocalWindowDragModifier provides windowDragModifier,
			LocalDragAreaCallback provides dragAreaCallback,
			LocalWindowControls provides windowControlsContent,
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

/**
 * Creates a drag [Modifier] for Linux that uses JBR's [WindowMove] API.
 *
 * On pointer press, [WindowMove.startMovingTogetherWithMouse] is called, which
 * sends a `_NET_WM_MOVERESIZE` client message to the X11 window manager. The
 * WM then takes over the drag operation, providing native behavior including
 * edge-snapping (tiling), drag-to-maximize, and workspace switching. Double-tap
 * toggles maximize / restore via [Frame.extendedState].
 *
 * If the [WindowMove] service is not available (e.g., on a native Wayland session
 * with JBR's `WLToolkit`), falls back to [fallbackDragModifier] which provides
 * Compose-level drag via [java.awt.Window.setLocation] — without WM-level
 * edge-snapping. Users on Wayland can work around this by launching with
 * `-Dawt.toolkit.name=XToolkit` to force XWayland.
 *
 * @param awtWindow The AWT frame to move.
 */
private fun linuxWmDragModifier(awtWindow: Frame): Modifier {
	val windowMove: WindowMove? = try {
		JBR.getWindowMove()
	} catch (_: Throwable) {
		null
	}
	if (windowMove == null) return fallbackDragModifier(awtWindow)
	
	return Modifier
		.pointerInput("double-tap") {
			detectTapGestures(
				onDoubleTap = {
					val maximised = awtWindow.extendedState and Frame.MAXIMIZED_BOTH != 0
					awtWindow.extendedState =
						if (maximised) Frame.NORMAL else Frame.MAXIMIZED_BOTH
				},
			)
		}
		.pointerInput("wm-drag") {
			awaitPointerEventScope {
				while (true) {
					val event = awaitPointerEvent()
					if (event.type == PointerEventType.Press &&
						event.changes.any { !it.isConsumed }
					) {
						windowMove.startMovingTogetherWithMouse(awtWindow, java.awt.event.MouseEvent.BUTTON1)
					}
				}
			}
		}
}

