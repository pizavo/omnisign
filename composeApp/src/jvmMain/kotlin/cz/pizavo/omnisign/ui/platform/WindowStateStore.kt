package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

/**
 * Persisted snapshot of the desktop window geometry and placement.
 *
 * Stores the last known *floating* (non-maximized) dimensions so the window
 * can be restored correctly regardless of whether it was closed while
 * maximized.
 *
 * @property width  Floating window width in density-independent pixels.
 * @property height Floating window height in density-independent pixels.
 * @property x      Floating window X position in density-independent pixels.
 * @property y      Floating window Y position in density-independent pixels.
 * @property placement Window placement at the time the application was closed.
 */
data class PersistedWindowState(
	val width: Float,
	val height: Float,
	val x: Float,
	val y: Float,
	val placement: WindowPlacement,
)

/**
 * Reads and writes desktop window geometry to a platform-native properties
 * file so the window can be restored to its previous size, position, and
 * placement (e.g., maximized) across application restarts.
 *
 * The file is stored alongside the main application configuration:
 * - **Windows**: `%APPDATA%/omnisign/window-state.properties`
 * - **macOS**: `~/Library/Application Support/omnisign/window-state.properties`
 * - **Linux**: `~/.config/omnisign/window-state.properties`
 */
object WindowStateStore {
	private const val FILE_NAME = "window-state.properties"

	private val path: Path by lazy { resolveStorePath() }

	/**
	 * Loads the previously persisted window state, or `null` when no state
	 * has been saved yet or the file is malformed.
	 */
	fun load(): PersistedWindowState? {
		if (!path.exists()) return null
		return try {
			val props = Properties()
			path.inputStream().use { props.load(it) }
			PersistedWindowState(
				width = props.getProperty("width")?.toFloatOrNull() ?: return null,
				height = props.getProperty("height")?.toFloatOrNull() ?: return null,
				x = props.getProperty("x")?.toFloatOrNull() ?: return null,
				y = props.getProperty("y")?.toFloatOrNull() ?: return null,
				placement = props.getProperty("placement")?.let {
					runCatching { WindowPlacement.valueOf(it) }.getOrNull()
				} ?: WindowPlacement.Floating,
			)
		} catch (e: Exception) {
			logger.warn(e) { "Failed to load window state from $path" }
			null
		}
	}

	/**
	 * Persists the given window geometry and placement to disk.
	 *
	 * @param placement Current [WindowPlacement].
	 * @param size      Last known *floating* window size.
	 * @param position  Last known *floating* window position.
	 */
	fun save(
		placement: WindowPlacement,
		size: DpSize,
		position: WindowPosition,
	) {
		try {
			path.parent?.createDirectories()
			val props = Properties()
			props.setProperty("width", size.width.value.toString())
			props.setProperty("height", size.height.value.toString())
			if (position is WindowPosition.Absolute) {
				props.setProperty("x", position.x.value.toString())
				props.setProperty("y", position.y.value.toString())
			}
			props.setProperty("placement", placement.name)
			path.outputStream().use { props.store(it, "OmniSign window state") }
		} catch (e: Exception) {
			logger.warn(e) { "Failed to save window state to $path" }
		}
	}

	/**
	 * Resolves the platform-specific directory for storing window state,
	 * matching the convention used by [cz.pizavo.omnisign.data.repository.FileConfigRepository].
	 */
	private fun resolveStorePath(): Path {
		val userHome = System.getProperty("user.home")
		val os = System.getProperty("os.name").lowercase()
		val dir = when {
			os.contains("win") ->
				Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "omnisign")

			os.contains("mac") ->
				Paths.get(userHome, "Library", "Application Support", "omnisign")

			else ->
				Paths.get(userHome, ".config", "omnisign")
		}
		return dir.resolve(FILE_NAME)
	}
}


