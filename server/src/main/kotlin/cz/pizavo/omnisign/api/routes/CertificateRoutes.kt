package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.model.responses.toResponse
import cz.pizavo.omnisign.api.requireOperation
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount certificate discovery routes under `/api/v1/certificates`.
 *
 * `GET /api/v1/certificates` is gated behind [AllowedOperation.SIGN] because it reveals
 * which signing certificates are installed on the server. The response includes signing-capable
 * certificates filtered by [ServerConfig.allowedCertificateAliases] (when set), plus any
 * per-token warnings and locked-token entries from the discovery process.
 *
 * Certificate discovery is not profile-scoped — available certificates depend only on the
 * server's hardware and software token configuration, not on the active profile.
 */
fun Route.certificateRoutes() {
	val listCertificatesUseCase by inject<ListCertificatesUseCase>()
	val serverConfig by inject<ServerConfig>()

	get("/api/v1/certificates") {
		if (!call.requireOperation(AllowedOperation.SIGN, serverConfig)) return@get

		listCertificatesUseCase().fold(
			ifLeft = { error ->
				throw OperationException(error)
			},
			ifRight = { result ->
				val allowedAliases = serverConfig.allowedCertificateAliases
				val filtered = if (allowedAliases != null) {
					result.copy(certificates = result.certificates.filter { it.alias in allowedAliases })
				} else {
					result
				}
				call.respond(filtered.toResponse())
			},
		)
	}
}

