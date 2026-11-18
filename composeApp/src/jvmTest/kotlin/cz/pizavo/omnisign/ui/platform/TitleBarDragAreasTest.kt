package cz.pizavo.omnisign.ui.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies that [TitleBarDragAreas] registers the drag areas Compose reports in physical pixels at
 * the window's display scale as it is when each area is registered, so that after the window moves
 * to a monitor with another scale an area does not spread over the toolbar buttons beside it.
 */
class TitleBarDragAreasTest : FunSpec({

	test("an area laid out on a monitor scaled to 150% is registered in logical units") {
		val areas = TitleBarDragAreas { 1.5 }

		areas.register("drag-left", Offset(120f, 30f), IntSize(581, 60))

		areas.contains(80, 20) shouldBe true
		areas.contains(466, 59) shouldBe true
		areas.contains(467, 30) shouldBe false
		areas.contains(79, 30) shouldBe false
		areas.contains(100, 19) shouldBe false
		areas.contains(100, 60) shouldBe false
	}

	test("an area laid out again after the window moves to a monitor with another scale is registered at the new scale, clear of the buttons beside it") {
		var scale = 1.0
		val areas = TitleBarDragAreas { scale }
		areas.register("drag-left", Offset(80f, 0f), IntSize(387, 40))

		scale = 1.5
		areas.register("drag-left", Offset(120f, 0f), IntSize(581, 60))

		areas.contains(466, 10) shouldBe true
		areas.contains(500, 10) shouldBe false
		areas.contains(100, 45) shouldBe false
	}

	test("an area registered again under its key replaces where it was") {
		val areas = TitleBarDragAreas { 1.0 }
		areas.register("drag-left", Offset(0f, 0f), IntSize(100, 40))

		areas.register("drag-left", Offset(200f, 0f), IntSize(100, 40))

		areas.contains(50, 20) shouldBe false
		areas.contains(250, 20) shouldBe true
	}

	test("a point lies in the drag areas when any of the registered areas holds it") {
		val areas = TitleBarDragAreas { 1.0 }
		areas.register("drag-left", Offset(0f, 0f), IntSize(100, 40))
		areas.register("drag-right", Offset(300f, 0f), IntSize(100, 40))

		areas.contains(50, 20) shouldBe true
		areas.contains(350, 20) shouldBe true
		areas.contains(200, 20) shouldBe false
	}
})
