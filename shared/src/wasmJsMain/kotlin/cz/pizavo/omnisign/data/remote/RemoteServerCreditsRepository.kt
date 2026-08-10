package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.api.model.responses.CreditsResponse
import cz.pizavo.omnisign.domain.repository.ServerCreditsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * Web-target [ServerCreditsRepository] implementation that fetches the connected server's
 * third-party credits over HTTP from `GET /api/v1/credits`.
 *
 * Uses content negotiation on the injected [HttpClient] to deserialize the JSON response into
 * [CreditsResponse]; configuration of the JSON settings and any default request headers belongs
 * in the Koin module that builds the client, not here.
 *
 * The endpoint is public on the server side, so this works before the user has logged in — which
 * matters, because the notices it carries are owed to anyone interacting with the deployment, not
 * only to those with an account. Callers must still tolerate failure: a server older than this
 * client answers 404, and the deployment may be unreachable entirely.
 *
 * @param client Pre-configured Ktor client with kotlinx-serialization content negotiation
 *   installed and a default request URL pointing at the server.
 */
class RemoteServerCreditsRepository(
    private val client: HttpClient,
) : ServerCreditsRepository {

    override suspend fun get(): CreditsResponse =
        client.get("api/v1/credits").body()
}
