package cz.pizavo.omnisign.ui.platform

/**
 * Opens the given local directory (or file) in the host OS's native file explorer
 * — Explorer on Windows, Finder on macOS, the default xdg-handler on Linux.
 *
 * Intended for "show this folder to the user" UX affordances, e.g. the PKCS#11
 * diagnostic dialog's drop-directory hint where the user has just downloaded a
 * library and wants to reveal the target folder rather than copy-pasting a path.
 *
 * The JVM implementation creates [path] as a directory if it doesn't already
 * exist, so users who have never run a discovery probe can still navigate to the
 * canonical drop location.  Web / non-desktop targets cannot open a local file
 * explorer and return `false` unconditionally.
 *
 * @param path Absolute filesystem path to open.
 * @return `true` if the platform accepted the request, `false` if the host has no
 *   file-explorer integration available or the underlying call failed (e.g. the
 *   path does not exist and could not be created).  Callers should treat `false`
 *   as a soft failure — the on-screen path text still tells the user where to go.
 */
expect fun openInFileExplorer(path: String): Boolean
