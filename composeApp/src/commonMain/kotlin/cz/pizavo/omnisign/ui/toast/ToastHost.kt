package cz.pizavo.omnisign.ui.toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.snackbar.Snackbar
import cz.pizavo.omnisign.lumo.components.snackbar.SnackbarDefaults

/**
 * Renders the active toast from [service] as a lumo [Snackbar], or nothing when no toast is
 * showing.  Stateless renderer — all timing and slot bookkeeping lives in [ToastService].
 *
 * Multiple [ToastHost] instances can — and are expected to — coexist in the composition:
 * one at the root layout, and one baked into every
 * [cz.pizavo.omnisign.lumo.components.Dialog].  All instances observe the same
 * `service.active` flow and render the same payload at whatever position their [modifier]
 * anchors them to.  Only one is visible at a time (the topmost OS window wins on Desktop),
 * which is exactly the seamless-handoff illusion we want: when the user closes a dialog
 * mid-toast the underlying root host is already rendering the same payload, so there's no
 * re-animation, no duration reset, no flicker.
 *
 * The visible/invisible transition uses [AnimatedVisibility] with a fade + scale animation,
 * mirroring the legacy `FadeInFadeOutWithScale` in `lumo.components.snackbar.SnackbarHost`.
 * A separate `lastToast` cache holds the previous payload so the exit animation still has
 * content to draw after [ToastService.active] flips to `null`.
 *
 * Action clicks route through [ToastService.performAction], which invokes the message's
 * `onAction` lambda and clears the slot for every live host.
 *
 * @param service The shared [ToastService] whose `active` flow drives this host.
 * @param modifier Modifier applied to the [AnimatedVisibility] root.  Typical callers
 *   anchor the host with `Modifier.align(Alignment.BottomEnd).padding(16.dp)` inside an
 *   outer [androidx.compose.foundation.layout.Box].
 * @param suppressWhenDialogOpen When `true`, this host disappears entirely while any lumo
 *   [cz.pizavo.omnisign.lumo.components.Dialog] is composed (see
 *   [ToastService.openDialogCount]).  Used by the root host in `IslandLayout` so the dialog's
 *   own toast host is the sole visible renderer while a dialog window is open — without this,
 *   a root window wider than the dialog would render its own toast outside the dialog area
 *   and the user would see two copies of the same toast at once.  Dialog-internal hosts pass
 *   the default `false`, so the seamless cross-window hand-off still works: when the dialog
 *   closes mid-toast the root un-suppresses and animates the toast in at its anchor.
 */
@Composable
fun ToastHost(
	service: ToastService,
	modifier: Modifier = Modifier,
	suppressWhenDialogOpen: Boolean = false,
) {
	val active by service.active.collectAsState()
	val openDialogs by service.openDialogCount.collectAsState()

	if (suppressWhenDialogOpen && openDialogs > 0) return

	val lastShown = remember { mutableStateOf<ActiveToast?>(null) }
	val current = active
	if (current != null) {
		lastShown.value = current
	}

	AnimatedVisibility(
		visible = current != null,
		modifier = modifier,
		enter = fadeIn() + scaleIn(initialScale = 0.85f),
		exit = fadeOut() + scaleOut(targetScale = 0.85f),
	) {
		val toast = lastShown.value ?: return@AnimatedVisibility
		val message = toast.message
		Snackbar(
			modifier = Modifier.padding(12.dp),
			actionContentColor = LumoTheme.colors.onPrimary,
			action = message.actionLabel?.let { label ->
				{
					SnackbarDefaults.ActionButton(text = label) { service.performAction() }
				}
			},
			content = {
				Text(
					text = message.text,
					style = LumoTheme.typography.body2,
				)
			},
		)
	}
}
