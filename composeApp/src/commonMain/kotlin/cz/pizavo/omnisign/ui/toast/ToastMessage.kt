package cz.pizavo.omnisign.ui.toast

/**
 * A single user-facing toast/snackbar payload, dispatched via [ToastService.show].
 *
 * Pure value type — production callers construct one and hand it to the service; the service
 * wraps it in an [ActiveToast] with an id+timing slot before publishing.  Multiple
 * [cz.pizavo.omnisign.ui.toast.ToastHost] renderers (root layout + any open dialog's
 * baked-in slot via [cz.pizavo.omnisign.lumo.components.Dialog]) then read that same active
 * payload from the service's [kotlinx.coroutines.flow.StateFlow] and render it at their
 * respective positions.  The hand-off across dialog boundaries (open / close mid-toast) is
 * lossless because all hosts observe the same underlying state.
 *
 * @property text The message body shown in the snackbar.  Keep short — Compose snackbars
 *   constrain content width and wrap awkwardly past ~2 lines.
 * @property actionLabel Optional label for an action button rendered on the right of the
 *   snackbar.  `null` (default) suppresses the button entirely.  When non-null pair it with
 *   [onAction]; clicks invoke [onAction] then clear the toast for every live host.
 * @property onAction Callback invoked when the user clicks the action button.  Ignored when
 *   [actionLabel] is `null` (the button is suppressed).
 * @property duration How long the toast stays visible.  Use [ToastDuration.Long] when
 *   [actionLabel] is non-null so the user has time to click.
 */
data class ToastMessage(
	val text: String,
	val actionLabel: String? = null,
	val onAction: (() -> Unit)? = null,
	val duration: ToastDuration = ToastDuration.Short,
)
