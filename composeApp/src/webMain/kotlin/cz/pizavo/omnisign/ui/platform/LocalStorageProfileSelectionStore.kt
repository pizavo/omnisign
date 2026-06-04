package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.remote.BrowserProfileSelectionStore
import kotlinx.browser.localStorage

/**
 * Default [BrowserProfileSelectionStore] backed by the browser's [localStorage].
 *
 * Lives in `composeApp/webMain` because `kotlinx.browser` is only on the classpath
 * here (transitively via Compose Multiplatform's wasmJs runtime) — the shared
 * module deliberately does not depend on it. The interface stays in
 * `shared/wasmJsMain` so [cz.pizavo.omnisign.data.remote.RemoteConfigRepository]
 * can declare its dependency without pulling in DOM bindings.
 *
 * The storage key is namespaced (`omnisign.activeProfile`) so it cannot collide
 * with unrelated browser-side state on the same origin.
 */
class LocalStorageProfileSelectionStore : BrowserProfileSelectionStore {
	override fun read(): String? = localStorage.getItem(KEY)?.takeIf { it.isNotBlank() }

	override fun write(name: String?) {
		if (name.isNullOrBlank()) {
			localStorage.removeItem(KEY)
		} else {
			localStorage.setItem(KEY, name)
		}
	}

	companion object {
		private const val KEY = "omnisign.activeProfile"
	}
}
