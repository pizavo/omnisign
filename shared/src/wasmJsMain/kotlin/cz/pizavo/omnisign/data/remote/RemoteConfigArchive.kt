package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * Web-target [ConfigArchivePort] that downloads the server's full configuration archive, letting a
 * web user carry a deployment's configuration to a desktop app.
 *
 * [exportFullConfig] fetches `GET /api/v1/config/export` — the very same ZIP the desktop Backup
 * export produces, assembled server-side from the configuration text plus the trusted certificates
 * it references. [importFullConfig] is unsupported: the server's configuration is provider-authored
 * and read-only over the API, so there is nothing to import into on this target.
 *
 * @param client Pre-configured Ktor client anchored at the OmniSign server (see [webDataModule]).
 */
class RemoteConfigArchive(
	private val client: HttpClient,
) : ConfigArchivePort {

	override suspend fun exportFullConfig(): OperationResult<ByteArray> =
		Either.catch {
			client.get("api/v1/config/export").body<ByteArray>()
		}.mapLeft { exception ->
			ConfigurationError.loadFromServerFailed(cause = exception)
		}

	override suspend fun importFullConfig(archive: ByteArray): OperationResult<Unit> =
		ConfigurationError.saveNotSupportedOnWeb(
			details = "The OmniSign server's configuration is provider-authored and read-only over the API",
		).left()
}
