package cz.pizavo.omnisign.lumo.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When `true`, Lumo input components in the current composition subtree render as read-only.
 *
 * Text fields, dropdowns, switches, checkboxes, and buttons disable themselves (greyed out, but
 * still showing their current value), while chips keep their normal appearance and only ignore
 * interaction — so a chip's selected state stays legible and is not confused with a separately
 * "locked" (greyed) chip.
 *
 * Wrap a form in `CompositionLocalProvider(LocalReadOnly provides true) { … }` to make the whole
 * subtree view-only without threading a flag through every field. Defaults to `false`, so any
 * composition that does not provide it behaves exactly as before.
 *
 * Components combine this with their own `enabled` parameter (effective enabled =
 * `enabled && !LocalReadOnly.current`), so an already-disabled control stays disabled regardless.
 */
val LocalReadOnly = staticCompositionLocalOf { false }
