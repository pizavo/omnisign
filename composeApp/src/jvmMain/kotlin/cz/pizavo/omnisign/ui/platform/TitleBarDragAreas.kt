package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap

/**
 * The parts of the custom title bar that drag the window, held as rectangles in the AWT logical units of
 * the window's content pane, which the timer that primes JBR's title-bar hit test compares the cursor
 * against.
 *
 * Compose lays the drag areas out in physical pixels, at the display scale of the monitor the window is
 * on, and lays them out again when the window moves to a monitor with another scale. [register] divides
 * by the scale [displayScale] gives at that moment, never by one read earlier. A scale kept from the
 * first monitor would register every area enlarged or shrunk by the ratio of the two scales, and an
 * enlarged area over a toolbar button makes the button drag the window instead of being pressed.
 *
 * @param displayScale Return the window's display scale as it is now, the factor from logical units to
 *   physical pixels; asked each time an area is registered.
 */
internal class TitleBarDragAreas(private val displayScale: () -> Double) {

	/** The registered areas, by the key their composable reports them under. */
	private val areas = ConcurrentHashMap<String, Rectangle>()

	/**
	 * Register the drag area [key], or move it, as laid out at [position] in the window with [size], both
	 * in physical pixels.
	 */
	fun register(key: String, position: Offset, size: IntSize) {
		val scale = displayScale()
		areas[key] = Rectangle(
			(position.x / scale).toInt(),
			(position.y / scale).toInt(),
			(size.width / scale).toInt(),
			(size.height / scale).toInt(),
		)
	}

	/** Whether the point at [x], [y], in the content pane's logical units, lies in a registered drag area. */
	fun contains(x: Int, y: Int): Boolean = areas.values.any { it.contains(x, y) }
}
