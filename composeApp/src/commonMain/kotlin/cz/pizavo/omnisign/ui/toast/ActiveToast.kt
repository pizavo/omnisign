package cz.pizavo.omnisign.ui.toast

/**
 * Internal envelope around a currently visible [ToastMessage].
 *
 * Published by [ToastService] as the value of its `active: StateFlow<ActiveToast?>`.  The [id]
 * field is the contract that makes superseding-toast bookkeeping correct: every call to
 * [ToastService.show] increments a monotonic counter, and the auto-dismiss coroutine launched
 * for *this* toast checks `active?.id == id` before resetting the state — so a toast
 * displaced by a newer one (or by an explicit [ToastService.dismiss]) cannot retroactively
 * clear the newer one when its own delay expires.
 *
 * Not data-equal across emissions intentionally: two consecutive [ToastService.show] calls
 * with the same [ToastMessage] still produce distinct [id]s, so [kotlinx.coroutines.flow.StateFlow]
 * (which conflates equal values) re-emits and live [ToastHost]s correctly re-animate.
 *
 * @property message The user-facing payload.
 * @property id Monotonic counter assigned by the service at publish time, used by the
 *   auto-dismiss coroutine to verify it still owns the slot before clearing it.
 */
data class ActiveToast(
	val message: ToastMessage,
	val id: Long,
)
