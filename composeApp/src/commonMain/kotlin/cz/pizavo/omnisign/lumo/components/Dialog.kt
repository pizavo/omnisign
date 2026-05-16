package cz.pizavo.omnisign.lumo.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.ui.toast.LocalToastService
import cz.pizavo.omnisign.ui.toast.ToastHost
import androidx.compose.ui.window.Dialog as ComposeDialog

/**
 * Lumo-styled modal dialog primitive shared by every full-screen dialog in the app.
 *
 * Behaves as a thin wrapper around [androidx.compose.ui.window.Dialog] plus a [Surface] in
 * the standard lumo dialog shape (16dp corners, surface colour, 8dp elevation), with two
 * goals:
 *
 * 1. **Collapses the boilerplate** that every dialog in `ui/layout/` was repeating —
 *    `Dialog(... properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(... shape = RoundedCornerShape(16.dp), color = LumoTheme.colors.surface, shadowElevation = 8.dp) { content } }`.
 *    Callers now write `Dialog(onDismissRequest, modifier = Modifier.widthIn(...).heightIn(...)) { content }`
 *    and inherit consistent chrome.
 *
 * 2. **Bakes the toast surface into every dialog automatically.**  Reads the application
 *    [cz.pizavo.omnisign.ui.toast.ToastService] from [LocalToastService] (provided once at
 *    `IslandLayout` root) and renders a bottom-right anchored
 *    [cz.pizavo.omnisign.ui.toast.ToastHost] inside the dialog's surface.  Combined with
 *    the root host, this is what makes a toast appear to persist across dialog open/close
 *    transitions: every live host observes the same shared `active: StateFlow`, so when
 *    the user closes a dialog mid-toast the underlying root host is already rendering the
 *    same payload — no re-animation, no duration reset, no flicker.  Dialogs used without
 *    a `LocalToastService` provided (previews, isolated tests) degrade silently — the
 *    toast slot simply doesn't render.
 *
 * Shadows [androidx.compose.ui.window.Dialog]; callers import this `Dialog` from
 * `cz.pizavo.omnisign.lumo.components` and stop needing the `Surface`+`DialogProperties`
 * boilerplate. The original Compose dialog is invoked internally via the
 * `androidx.compose.ui.window.Dialog as ComposeDialog` alias.
 *
 * @param onDismissRequest Invoked when the user attempts to dismiss the dialog (clicking
 *   outside, pressing Esc, etc.).  Caller decides whether to actually close.
 * @param modifier Modifier applied to the inner [Surface] — caller's primary lever for
 *   sizing (e.g. `Modifier.widthIn(min = 560.dp, max = 720.dp).heightIn(...)`).
 * @param shape Shape of the dialog surface.  Defaults to [DialogDefaults.Shape] (16dp
 *   rounded corners) for visual consistency across the app.
 * @param containerColor Background colour of the dialog surface.  Defaults to
 *   `LumoTheme.colors.surface`.
 * @param shadowElevation Elevation of the dialog surface.  Defaults to
 *   [DialogDefaults.Elevation] (8dp).
 * @param properties Underlying [DialogProperties].  Defaults to [DialogDefaults.Properties]
 *   which sets `usePlatformDefaultWidth = false` so the caller's [modifier] is the source
 *   of truth for sizing instead of the platform's narrow default.
 * @param showToast When `true` (default), the dialog embeds a bottom-right [ToastHost] and
 *   participates in the shared toast hand-off via [ToastService.incrementOpenDialogs].
 *   Set to `false` for small focused dialogs (e.g. PIN prompts) where there is neither
 *   room nor purpose for a toast — the root host keeps rendering toasts unaffected.
 * @param content Dialog body, rendered inside the [Surface] above the toast slot.  Typical
 *   shape: `Column(modifier = Modifier.fillMaxSize()) { … }`.
 */
@Composable
fun Dialog(
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	shape: Shape = DialogDefaults.Shape,
	containerColor: Color = LumoTheme.colors.surface,
	shadowElevation: Dp = DialogDefaults.Elevation,
	properties: DialogProperties = DialogDefaults.Properties,
	showToast: Boolean = true,
	content: @Composable () -> Unit,
) {
	ComposeDialog(
		onDismissRequest = onDismissRequest,
		properties = properties,
	) {
		Surface(
			modifier = modifier,
			shape = shape,
			color = containerColor,
			shadowElevation = shadowElevation,
		) {
			Box {
				content()
				val toastService = LocalToastService.current
				if (showToast && toastService != null) {
					DisposableEffect(toastService) {
						toastService.incrementOpenDialogs()
						onDispose { toastService.decrementOpenDialogs() }
					}
					ToastHost(
						service = toastService,
						modifier = Modifier
							.align(Alignment.BottomEnd)
							.padding(16.dp),
					)
				}
			}
		}
	}
}

/**
 * Default chrome values for the lumo [Dialog] primitive.  Centralised so the entire app
 * shares a single dialog look — change one constant here to retheme every dialog.
 */
object DialogDefaults {

	/** Default shape — 16dp rounded corners, matches every existing full-screen dialog. */
	val Shape: Shape = RoundedCornerShape(16.dp)

	/** Default surface elevation — 8dp, matches every existing full-screen dialog. */
	val Elevation: Dp = 8.dp

	/**
	 * Default [DialogProperties].  `usePlatformDefaultWidth = false` so the caller-supplied
	 * `modifier` (typically `widthIn`/`heightIn`) is the source of truth for sizing instead
	 * of the platform's narrow default (which would clamp our 560-920dp wide dialogs to
	 * ~280dp on Desktop).
	 */
	val Properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
}
