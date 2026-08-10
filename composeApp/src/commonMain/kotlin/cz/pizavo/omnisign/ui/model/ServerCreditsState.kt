package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.legal.ThirdPartyComponent

/**
 * State of the Credits dialog's server section: the components the connected OmniSign server
 * distributes, as opposed to the ones this build shipped to the user's own device.
 *
 * The distinction is the whole point of the section and must never collapse. A browser running the
 * web bundle downloads none of the signing stack — the PAdES work happens on the server, with EU
 * DSS — so merging the two lists would claim the browser shipped libraries it never received.
 * Keeping them apart lets the dialog credit what actually signs on the user's behalf while staying
 * truthful about where each component runs.
 */
sealed interface ServerCreditsState {

    /**
     * No server is involved, so the section is not rendered at all.
     *
     * This is the desktop target: it signs in-process, so the components doing that work are
     * already in its own credits list and a second section would only duplicate them.
     */
    data object NotApplicable : ServerCreditsState

    /** A fetch is in flight. */
    data object Loading : ServerCreditsState

    /**
     * The server answered with its credits.
     *
     * @property components The third-party components that deployment distributes.
     * @property license SPDX identifier of the server's own licence.
     * @property source Where the server's corresponding source can be obtained — the offer the GNU
     *   AGPL extends to a user interacting with it over a network, which is why it is surfaced next
     *   to the components rather than left to the API response alone.
     */
    data class Loaded(
        val components: List<ThirdPartyComponent>,
        val license: String,
        val source: String,
    ) : ServerCreditsState

    /**
     * The server could not be asked, or answered with nothing usable.
     *
     * Covers an unreachable deployment, a transport failure, and a server predating the credits
     * endpoint, which answers `404`. Rendered as a short note so the bundled list still shows.
     */
    data object Unavailable : ServerCreditsState
}
