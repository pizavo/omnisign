package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.api.model.responses.CreditsResponse

/**
 * Repository for retrieving the third-party credits of the server a client is talking to.
 *
 * Platform-agnostic contract — the web target binds an HTTP-backed implementation that hits
 * `GET /api/v1/credits`; future targets that speak to a remote server can bind their own
 * implementations against the same interface.
 *
 * There is no local implementation, and deliberately so. The desktop app performs its signing
 * in-process, so the components doing that work are already in its own credits list; a "server
 * credits" concept only means something when the work happens somewhere else. The browser bundle
 * is the opposite case: it contains none of the signing stack, so without this the person using
 * the web app has no way to learn that EU DSS — used under the GNU LGPL — is signing on their
 * behalf, nor to reach the offer of source the GNU AGPL extends to a network user.
 */
interface ServerCreditsRepository {
    /**
     * Fetches the credits published by the connected server.
     *
     * @return The server's [CreditsResponse]: the components that deployment distributes, plus
     *   OmniSign's own licence and source location.
     */
    suspend fun get(): CreditsResponse
}
