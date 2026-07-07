package cz.pizavo.omnisign.ui.platform

/**
 * Outcome of persisting produced bytes (a signed / extended document) to a user-chosen destination
 * via [saveDocument].
 *
 * The distinction between [Saved] and [SavedNameUnknown] drives whether the app can reopen the
 * result: desktop and the web File System Access API report the final name, whereas a plain browser
 * download does not (the browser never tells the page where the user put the file).
 */
sealed interface SaveOutcome {

	/**
	 * The bytes were written to a destination the app knows.
	 *
	 * @property path Full filesystem path on desktop, or the chosen file name on the web File System
	 *   Access API — enough to label and reopen the result, and (on desktop) to check renewal-job
	 *   coverage.
	 */
	data class Saved(val path: String) : SaveOutcome

	/**
	 * The bytes were handed to a browser download whose final name and location the app cannot
	 * observe (web fallback when the File System Access API is unavailable, e.g. Firefox / Safari).
	 * The file is saved, but it cannot be reopened in the viewer.
	 *
	 * @property downloadedAs The suggested file name the download was triggered with.
	 */
	data class SavedNameUnknown(val downloadedAs: String) : SaveOutcome

	/** The user dismissed the save dialog; nothing was written. */
	data object Cancelled : SaveOutcome

	/**
	 * Saving failed.
	 *
	 * @property reason Short human-readable failure description.
	 */
	data class Failed(val reason: String) : SaveOutcome
}
