package cz.pizavo.omnisign.ui.toast

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal carrying the application-wide [ToastService] down the composition tree.
 *
 * `IslandLayout` hoists the instance — `val toastService = remember { ToastService() }` —
 * so the same service can also be constructor-injected into ViewModels, then provides it
 * via `CompositionLocalProvider(LocalToastService provides toastService) { … }`.  It is
 * read inside every [cz.pizavo.omnisign.lumo.components.Dialog] to render its own
 * bottom-right [ToastHost].
 * Compose Multiplatform `Dialog` runs its content in a subcomposition that inherits the
 * parent CompositionLocals, so dialogs opened anywhere under `IslandLayout` see the same
 * service.
 *
 * Defaults to `null` so composables used outside a fully wired layout (previews, isolated
 * unit-style screens, the Wasm/web fallback path where `KoinPlatform.getKoinOrNull()` is
 * null and `IslandLayout` is the only thing that constructs the service) degrade silently
 * — a `Dialog` without a service simply skips the toast overlay, identical to a plain
 * `androidx.compose.ui.window.Dialog`.
 */
val LocalToastService = compositionLocalOf<ToastService?> { null }
