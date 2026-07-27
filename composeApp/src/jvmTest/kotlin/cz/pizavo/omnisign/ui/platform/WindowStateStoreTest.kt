package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Verifies [WindowStateStore] round-trips the desktop window geometry and, more importantly,
 * degrades quietly when it cannot.
 *
 * Every failure path here is swallowed by design: a window store that threw would take the whole
 * application down on launch over a preference nobody would miss. That makes the swallow worth
 * exercising — a corrupt or half-written file has to read back as "no saved state" so the window
 * opens at its default, rather than as an exception on the startup path.
 */
class WindowStateStoreTest : FunSpec({

	val tmpDir = tempdir()

	/** A store writing to a fresh file inside the spec's temp directory. */
	fun storeAt(fileName: String): Pair<WindowStateStore, Path> {
		val path = tmpDir.toPath().resolve(fileName)
		return WindowStateStore(path) to path
	}

	test("reports no state before anything has been saved") {
		val (store, _) = storeAt("absent.properties")

		store.load() shouldBe null
	}

	test("round-trips the floating geometry and placement") {
		val (store, _) = storeAt("roundtrip.properties")

		store.save(
			placement = WindowPlacement.Maximized,
			size = DpSize(1280.dp, 800.dp),
			position = WindowPosition.Absolute(100.dp, 50.dp),
		)

		val loaded = store.load().shouldNotBeNull()
		loaded.width shouldBe 1280f
		loaded.height shouldBe 800f
		loaded.x shouldBe 100f
		loaded.y shouldBe 50f
		loaded.placement shouldBe WindowPlacement.Maximized
	}

	test("keeps the floating geometry even when the window was closed maximized") {
		val (store, _) = storeAt("maximized.properties")

		store.save(
			placement = WindowPlacement.Maximized,
			size = DpSize(1024.dp, 768.dp),
			position = WindowPosition.Absolute(10.dp, 20.dp),
		)

		val loaded = store.load().shouldNotBeNull()
		loaded.width shouldBe 1024f
		loaded.placement shouldBe WindowPlacement.Maximized
	}

	test("creates the parent directory when saving for the first time") {
		val path = tmpDir.toPath().resolve("nested/deeper/window-state.properties")
		val store = WindowStateStore(path)

		store.save(
			placement = WindowPlacement.Floating,
			size = DpSize(800.dp, 600.dp),
			position = WindowPosition.Absolute(0.dp, 0.dp),
		)

		path.exists() shouldBe true
		store.load().shouldNotBeNull().width shouldBe 800f
	}

	test("reads no state from a file the property parser rejects") {
		val (store, path) = storeAt("garbage.properties")
		path.writeText("width=\\uZZZZ")

		store.load() shouldBe null
	}

	test("reads no state when a geometry value is missing") {
		val (store, path) = storeAt("partial.properties")
		path.writeText("height=800\nx=0\ny=0\nplacement=Floating\n")

		store.load() shouldBe null
	}

	test("reads no state when a geometry value is not a number") {
		val (store, path) = storeAt("nonnumeric.properties")
		path.writeText("width=wide\nheight=800\nx=0\ny=0\nplacement=Floating\n")

		store.load() shouldBe null
	}

	test("falls back to floating for a placement it does not recognise") {
		val (store, path) = storeAt("unknown-placement.properties")
		path.writeText("width=800\nheight=600\nx=0\ny=0\nplacement=FromANewerBuild\n")

		store.load().shouldNotBeNull().placement shouldBe WindowPlacement.Floating
	}

	test("omits the coordinates for a position the platform chose, leaving nothing to restore") {
		val (store, path) = storeAt("platform-default.properties")

		store.save(
			placement = WindowPlacement.Floating,
			size = DpSize(800.dp, 600.dp),
			position = WindowPosition.PlatformDefault,
		)

		path.exists() shouldBe true
		store.load() shouldBe null
	}

	test("swallows a save it cannot perform rather than taking the window down") {
		val path = tmpDir.toPath().resolve("occupied.properties")
		path.createDirectories()
		val store = WindowStateStore(path)

		store.save(
			placement = WindowPlacement.Floating,
			size = DpSize(800.dp, 600.dp),
			position = WindowPosition.Absolute(0.dp, 0.dp),
		)

		store.load() shouldBe null
	}
})
