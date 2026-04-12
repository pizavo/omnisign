package cz.pizavo.omnisign.ui.platform

/**
 * Loads the user's persisted preference for using the native (decorated) title bar
 * instead of the custom merged toolbar on Linux.
 *
 * @return `true` to use the native OS title bar, `false` for the merged custom
 *   toolbar, or `null` when no preference has been saved yet (platform default is used).
 */
expect fun loadUseNativeTitleBar(): Boolean?

/**
 * Persists the user's preference for native vs. custom title bar so it survives
 * application restarts.
 *
 * @param useNative `true` to use the native OS title bar on the next launch,
 *   `false` to use the merged custom toolbar.
 */
expect fun saveUseNativeTitleBar(useNative: Boolean)

