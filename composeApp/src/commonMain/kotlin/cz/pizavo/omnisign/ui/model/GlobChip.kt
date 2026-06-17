package cz.pizavo.omnisign.ui.model

/**
 * A renewal glob staged in the desktop chip input.
 *
 * @property glob The absolute glob pattern.
 * @property warning A human-readable reason the glob may match no files — a missing target
 *   directory, or a non-PDF extension — or `null` when it looks fine. A non-null value renders the
 *   chip as a warning; the glob is still accepted (the target may appear later, or the user may have
 *   a reason).
 */
data class GlobChip(
    val glob: String,
    val warning: String?,
)
