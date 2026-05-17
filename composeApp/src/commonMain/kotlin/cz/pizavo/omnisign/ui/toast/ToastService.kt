package cz.pizavo.omnisign.ui.toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-wide single-slot toast/snackbar dispatcher.
 *
 * Holds the currently visible [ActiveToast] as a [StateFlow] so that multiple
 * [ToastHost] renderers (the root one in `IslandLayout` plus the host baked into every
 * [cz.pizavo.omnisign.lumo.components.Dialog]) observe the same value and render the same
 * toast at the same position.  That's how a toast persists *visually* across dialog
 * open/close transitions — Compose Multiplatform `Dialog` opens a separate OS window per
 * modal on Desktop JVM (so no single composable can float above all windows), but every
 * window-scope hosts its own renderer reading the same shared state, and Compose's
 * [androidx.compose.animation.AnimatedVisibility] sees `active != null` continuously across
 * the hand-off so there's no re-animation, no flicker, no duration reset.
 *
 * The slot is single — a newer [show] supersedes any in-flight toast.  Auto-dismiss after
 * [ToastDuration.toMillis] uses an id check (see [ActiveToast.id]) so a displaced toast's
 * pending dismiss coroutine doesn't accidentally clear the new one.
 *
 * Constructed once in `IslandLayout` with `remember { ToastService() }` and provided to the
 * composition via the `LocalToastService` CompositionLocal.  ViewModels that need to emit
 * receive the service via constructor injection (see e.g. `SigningViewModel`).
 *
 * @param scope The scope on which auto-dismiss coroutines are launched.  Defaults to a fresh
 *   [SupervisorJob] on [Dispatchers.Default] (long-lived UI singleton).  Tests inject a
 *   [kotlinx.coroutines.test.TestScope]'s `backgroundScope` for deterministic timing.
 */
class ToastService(
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

	private val _active = MutableStateFlow<ActiveToast?>(null)

	/**
	 * The currently visible toast, or `null` when nothing is showing.
	 *
	 * Observed by every [ToastHost] in the composition.  Cleared either by the auto-dismiss
	 * coroutine (after [ToastDuration.toMillis]) or by an explicit [dismiss] / [performAction]
	 * call.  Replaced — not appended — by a fresh [show]; this is intentionally a single-slot
	 * surface, not a queue.
	 */
	val active: StateFlow<ActiveToast?> = _active.asStateFlow()

	private val _openDialogCount = MutableStateFlow(0)

	/**
	 * Number of lumo [cz.pizavo.omnisign.lumo.components.Dialog]s currently composed (and
	 * therefore likely visible as separate OS windows on Desktop).
	 *
	 * Each dialog increments this on enter and decrements on dispose via a `DisposableEffect`
	 * inside the lumo `Dialog` primitive.  [ToastHost] uses the count to suppress the root
	 * renderer while any dialog is open — the dialog's own host takes over, and the root
	 * un-suppresses when every dialog has closed.  This avoids the "two toasts visible at
	 * once" effect when a dialog's window doesn't fully cover the root window's bottom-right
	 * (which happens when the root is wider than the dialog).
	 */
	val openDialogCount: StateFlow<Int> = _openDialogCount.asStateFlow()

	private var dismissJob: Job? = null
	private var nextId: Long = 0L

	/**
	 * Publish [message] as the new active toast, replacing any in-flight one.
	 *
	 * Cancels the pending auto-dismiss for the previous toast (so it can't retroactively
	 * clear the newer one), increments the id counter, sets [active] to the new
	 * [ActiveToast], and — when [ToastMessage.duration] is bounded — launches a fresh
	 * auto-dismiss coroutine guarded by an id check.
	 *
	 * Safe to call from any thread / coroutine context; [MutableStateFlow] handles the
	 * atomic update.  The dismiss coroutine runs on the injected [scope].
	 */
	fun show(message: ToastMessage) {
		dismissJob?.cancel()
		val id = ++nextId
		_active.value = ActiveToast(message = message, id = id)
		val timeoutMs = message.duration.toMillis() ?: return
		dismissJob = scope.launch {
			delay(timeoutMs)
			if (_active.value?.id == id) {
				_active.value = null
			}
		}
	}

	/**
	 * Invoke the active toast's [ToastMessage.onAction] (if any) and clear the slot.
	 *
	 * Wired to action-button clicks on every [ToastHost] in the composition — whichever host
	 * the user actually clicked (the visible one, in the topmost window) routes through here,
	 * after which all hosts observe `_active.value == null` and animate out simultaneously.
	 */
	fun performAction() {
		val current = _active.value ?: return
		dismissJob?.cancel()
		_active.value = null
		current.message.onAction?.invoke()
	}

	/**
	 * Clear the active toast immediately without invoking [ToastMessage.onAction].
	 *
	 * Useful when the calling feature decides the toast is no longer relevant (e.g. user
	 * navigated away to a state where the message would be confusing).
	 */
	fun dismiss() {
		dismissJob?.cancel()
		_active.value = null
	}

	/**
	 * Register that a new lumo [cz.pizavo.omnisign.lumo.components.Dialog] has been composed.
	 *
	 * Called from the dialog primitive's `DisposableEffect` on enter.  Paired one-for-one with
	 * [decrementOpenDialogs] on dispose, so [openDialogCount] reflects the live dialog stack
	 * at any moment.
	 */
	fun incrementOpenDialogs() {
		_openDialogCount.value += 1
	}

	/**
	 * Register that a lumo [cz.pizavo.omnisign.lumo.components.Dialog] has been disposed.
	 *
	 * Called from the dialog primitive's `DisposableEffect` cleanup.  Floors at zero defensively
	 * — an underflow would indicate a paired-call bug elsewhere, not user-driven state.
	 */
	fun decrementOpenDialogs() {
		_openDialogCount.value = (_openDialogCount.value - 1).coerceAtLeast(0)
	}
}
