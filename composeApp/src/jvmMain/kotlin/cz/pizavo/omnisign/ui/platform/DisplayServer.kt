package cz.pizavo.omnisign.ui.platform

/**
 * Identifies the display server protocol in use on a Linux session.
 *
 * Used at startup to log diagnostics and to guard X11-specific code paths
 * (e.g., the `awtAppClassName` reflection hack and `_NET_WM_MOVERESIZE`-based
 * window drag). Detection relies on environment variables and the AWT toolkit
 * class name — no native bindings are required.
 *
 * @property label Human-readable label for log output.
 */
enum class DisplayServer(val label: String) {

	/** Native X11 session (e.g., GNOME on Xorg, KDE on X11). */
	X11("X11"),

	/** Wayland session running through XWayland compatibility (most JBR sessions). */
	XWAYLAND("XWayland"),

	/** Native Wayland session (JBR WLToolkit). */
	WAYLAND("Wayland"),

	/** Could not be determined. */
	UNKNOWN("unknown");

	companion object {

		/**
		 * Detects the display server for the current Linux session.
		 *
		 * Reads the AWT toolkit class name and environment variables from the
		 * running process, then delegates to the parameterized overload.
		 *
		 * @return The detected [DisplayServer].
		 */
		fun detect(): DisplayServer {
			val toolkitName = try {
				java.awt.Toolkit.getDefaultToolkit().javaClass.name
			} catch (_: Throwable) {
				""
			}
			return detect(
				toolkitClassName = toolkitName,
				xdgSessionType = System.getenv("XDG_SESSION_TYPE"),
				waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
			)
		}

		/**
		 * Detects the display server from the given indicators.
		 *
		 * Resolution order:
		 * 1. AWT toolkit class name — `sun.awt.wl.WLToolkit` → [WAYLAND],
		 *    `sun.awt.X11.XToolkit` → check env for XWayland.
		 * 2. `XDG_SESSION_TYPE` environment variable — `wayland` or `x11`.
		 * 3. Presence of `WAYLAND_DISPLAY` environment variable.
		 * 4. [UNKNOWN] if none of the above match.
		 *
		 * @param toolkitClassName Fully qualified class name of the AWT toolkit, or empty.
		 * @param xdgSessionType Value of the `XDG_SESSION_TYPE` environment variable, or `null`.
		 * @param waylandDisplay Value of the `WAYLAND_DISPLAY` environment variable, or `null`.
		 * @return The detected [DisplayServer].
		 */
		fun detect(
			toolkitClassName: String,
			xdgSessionType: String?,
			waylandDisplay: String?,
		): DisplayServer {
			if (toolkitClassName.contains("WLToolkit", ignoreCase = true)) return WAYLAND

			val sessionType = xdgSessionType?.lowercase()
			val hasWaylandDisplay = waylandDisplay != null

			if (toolkitClassName.contains("XToolkit", ignoreCase = true)) {
				return if (sessionType == "wayland" || hasWaylandDisplay) XWAYLAND else X11
			}

			return when {
				sessionType == "x11" -> X11
				sessionType == "wayland" && hasWaylandDisplay -> XWAYLAND
				sessionType == "wayland" -> WAYLAND
				hasWaylandDisplay -> XWAYLAND
				else -> UNKNOWN
			}
		}
	}
}
