package cz.pizavo.omnisign.ui.platform

/**
 * Sets the host document / window title to [title].
 *
 * On the web target this updates the browser tab title (`document.title`); the desktop target owns its
 * window title through the `Window` composable and treats this as a no-op. Used to refresh the tab once
 * the server's `organizationName` arrives with the capabilities, so a white-label chain's full
 * `"<deployer> · <operator> · OmniSign"` title appears without blocking boot on a server round-trip.
 */
expect fun updateDocumentTitle(title: String)
