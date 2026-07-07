@file:OptIn(ExperimentalWasmJsInterop::class)

package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.set

/** Whether the browser exposes the File System Access API save picker (Chromium; not Firefox/Safari). */
private fun hasFileSystemAccess(): Boolean = js("typeof window.showSaveFilePicker === 'function'")

/**
 * Save [bytes] through `showSaveFilePicker`, resolving to a `"<status>:<name>"` string where status
 * is `saved` (with the chosen file name), `cancelled`, or `error`. Encoded as a single string rather
 * than a JS object so the Wasm↔JS boundary stays a plain `Promise<JsString>`.
 */
private fun saveWithPicker(bytes: Int8Array, suggestedName: String): Promise<JsString> =
	js("(async () => { try { const h = await window.showSaveFilePicker({ suggestedName: suggestedName, types: [{ description: 'PDF document', accept: { 'application/pdf': ['.pdf'] } }] }); const w = await h.createWritable(); await w.write(bytes); await w.close(); return 'saved:' + h.name; } catch (e) { return (e && e.name === 'AbortError') ? 'cancelled:' : 'error:'; } })()")

/** Copy a [ByteArray] into a JS [Int8Array] so it can cross into `showSaveFilePicker` as a `BufferSource`. */
private fun ByteArray.toInt8Array(): Int8Array {
	val array = Int8Array(size)
	for (index in indices) array[index] = this[index]
	return array
}

/**
 * Wasm/JS implementation.
 *
 * When the browser supports the File System Access API, `showSaveFilePicker` opens a real "Save As"
 * dialog and reports the chosen file name ([SaveOutcome.Saved]) — so the app can reopen the result.
 * Otherwise (Firefox / Safari / mobile) it degrades to a plain browser download whose final name and
 * location the page cannot observe ([SaveOutcome.SavedNameUnknown]). A cancelled picker is
 * [SaveOutcome.Cancelled]; any picker error (including a missing user gesture) also degrades to a
 * download so the file is never lost.
 */
actual suspend fun saveDocument(
	bytes: ByteArray,
	suggestedName: String,
	extension: String,
	initialDirectory: String?,
): SaveOutcome {
	val fileName = "$suggestedName.$extension"
	if (!hasFileSystemAccess()) return downloadFallback(bytes, fileName)
	val result: JsString = saveWithPicker(bytes.toInt8Array(), fileName).await()
	val raw = result.toString()
	return when {
		raw.startsWith("saved:") -> SaveOutcome.Saved(raw.substringAfter(':'))
		raw.startsWith("cancelled:") -> SaveOutcome.Cancelled
		else -> downloadFallback(bytes, fileName)
	}
}

/** Save via FileKit's browser-download flow, whose final name / location the page cannot observe. */
private suspend fun downloadFallback(bytes: ByteArray, fileName: String): SaveOutcome =
	try {
		FileKit.download(bytes = bytes, fileName = fileName)
		SaveOutcome.SavedNameUnknown(fileName)
	} catch (e: Throwable) {
		SaveOutcome.Failed(e.message ?: "Browser download failed")
	}
