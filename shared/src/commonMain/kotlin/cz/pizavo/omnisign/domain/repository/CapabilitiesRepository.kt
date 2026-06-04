package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse

/**
 * Repository for retrieving the server's published capabilities.
 *
 * Platform-agnostic contract — the web target binds an HTTP-backed
 * implementation that hits `GET /api/v1/capabilities`; future targets that
 * speak to a remote server can bind their own implementations against the
 * same interface.
 *
 * There is no local (DSS-backed) implementation: "capabilities" is a
 * server-introspection concept that only makes sense in a client-server
 * deployment.
 */
interface CapabilitiesRepository {
    /**
     * Fetches the server's current capabilities.
     *
     * @return The server's [CapabilitiesResponse] describing allowed
     *   operations, available profiles, the upload size limit, and whether
     *   authentication is required.
     */
    suspend fun get(): CapabilitiesResponse
}
