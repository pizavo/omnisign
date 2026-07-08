package cz.pizavo.omnisign.ui.platform

import kotlinx.browser.document

/**
 * Wasm / JS implementation — updates the browser tab title.
 */
actual fun updateDocumentTitle(title: String) {
    document.title = title
}
