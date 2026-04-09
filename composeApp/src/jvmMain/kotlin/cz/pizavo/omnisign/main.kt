package cz.pizavo.omnisign

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
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
 * Interval in milliseconds for the [Timer] that primes
 * [WindowDecorations.CustomTitleBar.forceHitTest].
 */
private const val FORCE_HIT_TEST_POLL_MS = 8

/**
 * `true` when the app is running on Linux (or another non-Windows, non-macOS OS).
 *
 * JBR's [com.jetbrains.WindowDecorations] API is not supported on Linux, so the
 * window runs as **undecorated** there and custom window-control buttons are shown
 * inside the Compose toolbar via [LocalWindowControls].
 */
private val isLinux: Boolean = System.getProperty("os.name").lowercase().let {
	!it.contains("win") && !it.contains("mac")
}

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
	if (System.getProperty("os.name", "").lowercase().contains("linux")) {
		try {
			java.awt.Toolkit.getDefaultToolkit() // ensure XToolkit.<init> has already run
			val cls = Class.forName("sun.awt.X11.XToolkit")
			val fld = cls.getDeclaredField("awtAppClassName")
			fld.isAccessible = true
			fld.set(null, "OmniSign")
		} catch (_: Exception) {
			// Non-X11 display (Wayland-native, headless) or restricted JVM – ignore.
		}
	}

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
		undecorated = isLinux,
		transparent = false,
		resizable = true,
		state = windowState,
		title = "OmniSign",
	) {
		val awtWindow = window

		remember(windowIcon) {
			windowIcon?.toAwtImage()?.let { awtWindow.iconImage = it }
		}

		val titleBarHeightPx = TITLE_BAR_HEIGHT_DP.toFloat()
		
		// On Linux, JBR WindowDecorations is not supported — the window is undecorated
		// and custom controls are provided via LocalWindowControls instead.
		val titleBar: WindowDecorations.CustomTitleBar? =
			remember { if (isLinux) null else JbrTitleBarHelper.install(awtWindow, titleBarHeightPx) }
		
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
		
		val windowControlsContent: (@Composable () -> Unit)? = if (isLinux) {
			{ LinuxWindowControls(awtWindow) }
		} else null
		
		CompositionLocalProvider(
			LocalTitleBarHeight provides TITLE_BAR_HEIGHT_DP.dp,
			LocalTitleBarRightInset provides rightInsetPx,
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
