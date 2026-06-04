package cz.pizavo.omnisign.data.remote

/**
 * Web-target persistent store for the user's selected active-profile name.
 *
 * Implementations are expected to be backed by browser storage (typically
 * `localStorage`) so the selection survives a page reload. Per-browser, per-origin
 * — opening a private window or signing in from a different device starts from
 * "no selection", matching how a client-side preference is expected to behave.
 *
 * The store holds only the name; the server's profile list (delivered via
 * [cz.pizavo.omnisign.data.remote.RemoteConfigRepository]) is what eventually
 * validates whether the name still exists. The server itself has no notion of an
 * active profile — every operation request carries the current selection
 * explicitly and the server applies it for that single call.
 *
 * Implementations live in the consuming module (composeApp/webMain) so the
 * `kotlinx.browser` dependency does not leak into the shared module. The
 * interface stays here so [RemoteConfigRepository] can depend on it.
 */
interface BrowserProfileSelectionStore {
	/**
	 * Read the persisted active-profile name.
	 *
	 * @return The persisted name, or `null` when no entry is set or the entry is blank.
	 */
	fun read(): String?

	/**
	 * Persist [name] as the new active profile.
	 *
	 * Removes the entry instead of writing an empty string when [name] is `null` or
	 * blank — so subsequent reads observe "no selection" rather than a sentinel
	 * empty value.
	 *
	 * @param name The profile name to persist, or `null` to clear the selection.
	 */
	fun write(name: String?)
}
