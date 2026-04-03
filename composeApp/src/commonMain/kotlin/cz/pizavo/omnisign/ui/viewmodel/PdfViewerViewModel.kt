package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
import cz.pizavo.omnisign.ui.model.PdfViewerState
import cz.pizavo.omnisign.ui.model.PdfViewerState.Companion.DEFAULT_ZOOM
import cz.pizavo.omnisign.ui.model.PdfViewerState.Companion.MAX_ZOOM
import cz.pizavo.omnisign.ui.model.PdfViewerState.Companion.MIN_ZOOM
import cz.pizavo.omnisign.ui.model.PdfViewerState.Companion.ZOOM_STEP
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel managing the state of the PDF document viewer.
 *
 * Holds the currently loaded [PdfDocumentInfo], the active page index, and the
 * zoom level. The document remains in memory until a new file is selected or the
 * hosting scope (window / activity) is destroyed.
 */
class PdfViewerViewModel : ViewModel() {

    private val _state = MutableStateFlow(PdfViewerState())

    /** Observable viewer state. */
    val state: StateFlow<PdfViewerState> = _state.asStateFlow()

    /**
     * Replaces the currently displayed document and resets the page and zoom level.
     *
     * @param document The newly loaded PDF, or `null` to clear the viewer.
     */
    fun onDocumentLoaded(document: PdfDocumentInfo?) {
        _state.update { current ->
            if (document != null) {
                current.copy(document = document, currentPage = 0, zoomLevel = DEFAULT_ZOOM)
            } else {
                current
            }
        }
    }

    /** Navigates to the given zero-based [page], clamped to valid bounds. */
    fun goToPage(page: Int) {
        _state.update { current ->
            val doc = current.document ?: return@update current
            current.copy(currentPage = page.coerceIn(0, doc.pageCount - 1))
        }
    }

    /** Advances to the next page if not already at the last one. */
    fun nextPage() {
        goToPage(_state.value.currentPage + 1)
    }

    /** Returns to the previous page if not already at the first one. */
    fun previousPage() {
        goToPage(_state.value.currentPage - 1)
    }

    /** Increases the zoom level by [ZOOM_STEP], clamped to [MAX_ZOOM]. */
    fun zoomIn() {
        _state.update { current ->
            current.copy(zoomLevel = (current.zoomLevel + ZOOM_STEP).coerceAtMost(MAX_ZOOM))
        }
    }

    /** Decreases the zoom level by [ZOOM_STEP], clamped to [MIN_ZOOM]. */
    fun zoomOut() {
        _state.update { current ->
            current.copy(zoomLevel = (current.zoomLevel - ZOOM_STEP).coerceAtLeast(MIN_ZOOM))
        }
    }

    /** Resets the zoom level back to [DEFAULT_ZOOM]. */
    fun resetZoom() {
        _state.update { current ->
            current.copy(zoomLevel = DEFAULT_ZOOM)
        }
    }
}

