package cz.pizavo.omnisign.testing

import cz.pizavo.omnisign.data.remote.BrowserProfileSelectionStore

/**
 * In-memory [BrowserProfileSelectionStore] standing in for the browser's `localStorage`.
 *
 * Hand-written rather than mocked because MockK is a JVM-only library and these specs run on
 * Wasm/JS. Records every write so a spec can assert not just the resulting selection but that the
 * repository actively cleared an orphaned one.
 *
 * @param initial The selection the store starts out holding, as a prior session would have left it.
 */
class RecordingProfileSelectionStore(initial: String? = null) : BrowserProfileSelectionStore {

	/** The currently persisted selection. */
	var stored: String? = initial
		private set

	/** Every value written, in order, including the `null`s that clear the selection. */
	val writes: MutableList<String?> = mutableListOf()

	override fun read(): String? = stored

	override fun write(name: String?) {
		stored = name
		writes += name
	}
}
