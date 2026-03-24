package cz.pizavo.omnisign.ui.model

/**
 * Describes a loaded PDF document held in memory.
 *
 * @property name Display the name of the file.
 * @property data Raw PDF bytes.
 * @property pageCount Total number of pages.
 */
data class PdfDocumentInfo(
    val name: String,
    val data: ByteArray,
    val pageCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfDocumentInfo) return false
        return name == other.name && pageCount == other.pageCount && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + pageCount
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * UI state for the PDF viewer.
 *
 * @property document Currently loaded PDF document, or `null` when no document is open.
 * @property currentPage Zero-based index of the currently displayed page.
 * @property zoomLevel User-controlled zoom multiplier applied to the page width.
 *   `1.0` means 100 %; valid range is [MIN_ZOOM]..[MAX_ZOOM].
 */
data class PdfViewerState(
    val document: PdfDocumentInfo? = null,
    val currentPage: Int = 0,
    val zoomLevel: Float = DEFAULT_ZOOM,
) {
    companion object {
        /** Minimum allowed zoom multiplier (25 %). */
        const val MIN_ZOOM: Float = 0.25f

        /** Maximum allowed zoom multiplier (400 %). */
        const val MAX_ZOOM: Float = 4.0f

        /** Default zoom multiplier (100 %). */
        const val DEFAULT_ZOOM: Float = 1.0f

        /** Increment / decrement step for a single zoom action. */
        const val ZOOM_STEP: Float = 0.25f
    }
}


