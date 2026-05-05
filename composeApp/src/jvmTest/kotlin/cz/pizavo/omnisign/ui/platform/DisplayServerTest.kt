package cz.pizavo.omnisign.ui.platform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [DisplayServer.detect] covering each resolution path.
 *
 * The detection logic depends on `java.awt.Toolkit.getDefaultToolkit()` and
 * environment variables. Tests use MockK static mocking on [System.getenv] to
 * simulate different Linux sessions.
 */
class DisplayServerTest : FunSpec({

	test("WAYLAND is returned when toolkit is WLToolkit") {
		val server = DisplayServer.detect(
			toolkitClassName = "sun.awt.wl.WLToolkit",
			xdgSessionType = null,
			waylandDisplay = null,
		)
		server shouldBe DisplayServer.WAYLAND
	}

	test("XWAYLAND is returned when toolkit is XToolkit and XDG_SESSION_TYPE is wayland") {
		val server = DisplayServer.detect(
			toolkitClassName = "sun.awt.X11.XToolkit",
			xdgSessionType = "wayland",
			waylandDisplay = null,
		)
		server shouldBe DisplayServer.XWAYLAND
	}

	test("XWAYLAND is returned when toolkit is XToolkit and WAYLAND_DISPLAY is set") {
		val server = DisplayServer.detect(
			toolkitClassName = "sun.awt.X11.XToolkit",
			xdgSessionType = null,
			waylandDisplay = "wayland-0",
		)
		server shouldBe DisplayServer.XWAYLAND
	}

	test("X11 is returned when toolkit is XToolkit and no Wayland indicators are present") {
		val server = DisplayServer.detect(
			toolkitClassName = "sun.awt.X11.XToolkit",
			xdgSessionType = "x11",
			waylandDisplay = null,
		)
		server shouldBe DisplayServer.X11
	}

	test("X11 is returned from env fallback when XDG_SESSION_TYPE is x11") {
		val server = DisplayServer.detect(
			toolkitClassName = "",
			xdgSessionType = "x11",
			waylandDisplay = null,
		)
		server shouldBe DisplayServer.X11
	}

	test("XWAYLAND is returned from env fallback when XDG_SESSION_TYPE is wayland with WAYLAND_DISPLAY") {
		val server = DisplayServer.detect(
			toolkitClassName = "",
			xdgSessionType = "wayland",
			waylandDisplay = "wayland-0",
		)
		server shouldBe DisplayServer.XWAYLAND
	}

	test("WAYLAND is returned from env fallback when XDG_SESSION_TYPE is wayland without WAYLAND_DISPLAY") {
		val server = DisplayServer.detect(
			toolkitClassName = "",
			xdgSessionType = "wayland",
			waylandDisplay = null,
		)
		server shouldBe DisplayServer.WAYLAND
	}

	test("XWAYLAND is returned when only WAYLAND_DISPLAY is set") {
		val server = DisplayServer.detect(
			toolkitClassName = "",
			xdgSessionType = null,
			waylandDisplay = "wayland-1",
		)
		server shouldBe DisplayServer.XWAYLAND
	}

	test("UNKNOWN is returned when no toolkit or env indicators are present") {
		val server = DisplayServer.detect(
			toolkitClassName = "",
			xdgSessionType = null,
			waylandDisplay = null,
		)
		server shouldBe DisplayServer.UNKNOWN
	}

	test("label property returns human-readable strings") {
		DisplayServer.X11.label shouldBe "X11"
		DisplayServer.XWAYLAND.label shouldBe "XWayland"
		DisplayServer.WAYLAND.label shouldBe "Wayland"
		DisplayServer.UNKNOWN.label shouldBe "unknown"
	}
})


