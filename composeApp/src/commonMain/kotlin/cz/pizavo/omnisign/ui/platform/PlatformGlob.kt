package cz.pizavo.omnisign.ui.platform

/**
 * Whether [glob]'s root (the literal prefix before its first wildcard) is an absolute path on this
 * platform. Renewal globs must be absolute, but what counts as absolute is platform-specific (on
 * Windows a drive-less `/docs/...` is *not* absolute), so the check is resolved per target.
 */
expect fun isAbsoluteGlob(glob: String): Boolean

/**
 * A platform-appropriate example of an absolute glob, for placeholders.
 */
expect fun absoluteGlobExample(): String

/**
 * Whether [glob]'s target directory (or file, for a literal pattern) currently exists. Used only to
 * warn, not to block. Platforms without a filesystem (web) always report `true`.
 */
expect fun globTargetExists(glob: String): Boolean

/**
 * Whether [glob] still needs a file pattern (no wildcard and not an existing file) — which matches
 * no files and is therefore rejected. Platforms without a filesystem (web) always report `false`.
 */
expect fun globNeedsFilePattern(glob: String): Boolean

/**
 * Whether [glob]'s wildcard tail is a pattern the platform can parse; an unclosed `[` or `{` is not.
 * Rejected here so it never reaches a scheduled run, where it would abort the whole batch. Platforms
 * without a filesystem (web) always report `true`, leaving the check to the run, which guards it too.
 */
expect fun isParseableGlob(glob: String): Boolean
