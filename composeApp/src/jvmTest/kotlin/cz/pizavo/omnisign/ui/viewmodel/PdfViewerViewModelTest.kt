package cz.pizavo.omnisign.ui.viewmodel

import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
import cz.pizavo.omnisign.ui.model.PdfViewerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [PdfViewerViewModel].
 */
class PdfViewerViewModelTest : FunSpec({

    test("initial state has no document") {
        val vm = PdfViewerViewModel()
        val state = vm.state.value

        state.document.shouldBeNull()
        state.currentPage shouldBe 0
        state.zoomLevel shouldBe PdfViewerState.DEFAULT_ZOOM
    }

    test("onDocumentLoaded stores document and resets page") {
        val vm = PdfViewerViewModel()
        val pdf = PdfDocumentInfo(name = "test.pdf", data = byteArrayOf(1, 2, 3), pageCount = 5)

        vm.onDocumentLoaded(pdf)

        val state = vm.state.value
        val doc = state.document.shouldNotBeNull()
        doc.name shouldBe "test.pdf"
        doc.pageCount shouldBe 5
        state.currentPage shouldBe 0
    }

    test("onDocumentLoaded with null keeps existing document") {
        val vm = PdfViewerViewModel()
        val pdf = PdfDocumentInfo(name = "test.pdf", data = byteArrayOf(1), pageCount = 3)
        vm.onDocumentLoaded(pdf)

        vm.onDocumentLoaded(null)

        val doc = vm.state.value.document.shouldNotBeNull()
        doc.name shouldBe "test.pdf"
    }

    test("nextPage and previousPage navigate within bounds") {
        val vm = PdfViewerViewModel()
        val pdf = PdfDocumentInfo(name = "doc.pdf", data = byteArrayOf(0), pageCount = 3)
        vm.onDocumentLoaded(pdf)

        vm.state.value.currentPage shouldBe 0

        vm.nextPage()
        vm.state.value.currentPage shouldBe 1

        vm.nextPage()
        vm.state.value.currentPage shouldBe 2

        vm.nextPage()
        vm.state.value.currentPage shouldBe 2

        vm.previousPage()
        vm.state.value.currentPage shouldBe 1

        vm.previousPage()
        vm.state.value.currentPage shouldBe 0

        vm.previousPage()
        vm.state.value.currentPage shouldBe 0
    }

    test("goToPage clamps to valid range") {
        val vm = PdfViewerViewModel()
        val pdf = PdfDocumentInfo(name = "doc.pdf", data = byteArrayOf(0), pageCount = 5)
        vm.onDocumentLoaded(pdf)

        vm.goToPage(3)
        vm.state.value.currentPage shouldBe 3

        vm.goToPage(100)
        vm.state.value.currentPage shouldBe 4

        vm.goToPage(-5)
        vm.state.value.currentPage shouldBe 0
    }

    test("goToPage is no-op when no document is loaded") {
        val vm = PdfViewerViewModel()
        vm.goToPage(5)

        vm.state.value.currentPage shouldBe 0
    }

    test("selecting a new file replaces the previous document and resets page") {
        val vm = PdfViewerViewModel()
        val first = PdfDocumentInfo(name = "first.pdf", data = byteArrayOf(1), pageCount = 10)
        vm.onDocumentLoaded(first)
        vm.goToPage(7)

        val second = PdfDocumentInfo(name = "second.pdf", data = byteArrayOf(2), pageCount = 3)
        vm.onDocumentLoaded(second)

        val state = vm.state.value
        val doc = state.document.shouldNotBeNull()
        doc.name shouldBe "second.pdf"
        state.currentPage shouldBe 0
    }

    test("zoomIn increases zoom by ZOOM_STEP") {
        val vm = PdfViewerViewModel()

        vm.zoomIn()
        vm.state.value.zoomLevel shouldBe (PdfViewerState.DEFAULT_ZOOM + PdfViewerState.ZOOM_STEP)

        vm.zoomIn()
        vm.state.value.zoomLevel shouldBe (PdfViewerState.DEFAULT_ZOOM + 2 * PdfViewerState.ZOOM_STEP)
    }

    test("zoomOut decreases zoom by ZOOM_STEP") {
        val vm = PdfViewerViewModel()
        vm.zoomIn()
        vm.zoomIn()

        vm.zoomOut()
        vm.state.value.zoomLevel shouldBe (PdfViewerState.DEFAULT_ZOOM + PdfViewerState.ZOOM_STEP)
    }

    test("zoomIn clamps at MAX_ZOOM") {
        val vm = PdfViewerViewModel()
        repeat(100) { vm.zoomIn() }

        vm.state.value.zoomLevel shouldBe PdfViewerState.MAX_ZOOM
    }

    test("zoomOut clamps at MIN_ZOOM") {
        val vm = PdfViewerViewModel()
        repeat(100) { vm.zoomOut() }

        vm.state.value.zoomLevel shouldBe PdfViewerState.MIN_ZOOM
    }

    test("resetZoom returns to DEFAULT_ZOOM") {
        val vm = PdfViewerViewModel()
        vm.zoomIn()
        vm.zoomIn()
        vm.zoomIn()

        vm.resetZoom()

        vm.state.value.zoomLevel shouldBe PdfViewerState.DEFAULT_ZOOM
    }

    test("onDocumentLoaded resets zoom level") {
        val vm = PdfViewerViewModel()
        val first = PdfDocumentInfo(name = "a.pdf", data = byteArrayOf(1), pageCount = 2)
        vm.onDocumentLoaded(first)
        vm.zoomIn()
        vm.zoomIn()
        vm.state.value.zoomLevel shouldBe (PdfViewerState.DEFAULT_ZOOM + 2 * PdfViewerState.ZOOM_STEP)

        val second = PdfDocumentInfo(name = "b.pdf", data = byteArrayOf(2), pageCount = 3)
        vm.onDocumentLoaded(second)

        vm.state.value.zoomLevel shouldBe PdfViewerState.DEFAULT_ZOOM
    }
})



