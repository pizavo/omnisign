package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * Web-target [CapabilitiesRepository] implementation that fetches the server's
 * capabilities over HTTP from `GET /api/v1/capabilities`.
 *
 * Uses content negotiation on the injected [HttpClient] to deserialize the
 * JSON response into [CapabilitiesResponse]; configuration of the JSON
 * settings and any default request headers belongs in the Koin module that
 * builds the client, not here.
 *
 * @param client Pre-configured Ktor client with kotlinx-serialization content
 *   negotiation installed and a default request URL pointing at the server.
 */
class RemoteCapabilitiesRepository(
    private val client: HttpClient,
) : CapabilitiesRepository {

    override suspend fun get(): CapabilitiesResponse =
        client.get("api/v1/capabilities").body()
}
