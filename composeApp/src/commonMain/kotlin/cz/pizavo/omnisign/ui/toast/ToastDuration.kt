package cz.pizavo.omnisign.ui.toast

/**
 * How long a [ToastMessage] stays visible after [ToastService.show] before it auto-dismisses.
 *
 * Mirrors the timings of [cz.pizavo.omnisign.lumo.components.snackbar.SnackbarDuration] so that
 * a toast feels at home next to anything else built on the lumo snackbar primitives, but is
 * intentionally a separate type — toasts live in a different state model (single shared
 * `StateFlow<ActiveToast?>`, not a per-host mutex queue) and shouldn't be coupled to the
 * lumo enum in case the two diverge.
 *
 * Pick [Short] for purely informational acknowledgements, [Long] when the toast carries an
 * action button the user needs time to read and click, and [Indefinite] only when the toast
 * should stay until the user explicitly performs the action or another toast supersedes it.
 */
enum class ToastDuration {
	/** ~4 seconds — informational acknowledgements with no action. */
	Short,

	/** ~10 seconds — toasts with an action button so the user has time to read and click. */
	Long,

	/** No auto-dismiss — stays until [ToastService.performAction] or [ToastService.dismiss] is called, or a new toast supersedes it. */
	Indefinite;

	/**
	 * Return the auto-dismiss timeout in milliseconds, or `null` when the toast should stay
	 * until explicitly dismissed.  Consumed by [ToastService.show] to schedule the auto-clear
	 * coroutine; null skips scheduling entirely.
	 */
	internal fun toMillis(): Long? = when (this) {
		Short -> 4_000L
		Long -> 10_000L
		Indefinite -> null
	}
}
