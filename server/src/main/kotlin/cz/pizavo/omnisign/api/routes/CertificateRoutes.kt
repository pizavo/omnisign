package cz.pizavo.omnisign.api.routes

import arrow.core.getOrElse
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.requireOperation
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.ListKeystoreCertificatesUseCase
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount certificate discovery routes under `/api/v1/certificates`.
 *
 * `GET /api/v1/certificates` is gated behind [AllowedOperation.SIGN] because it reveals
 * which signing certificates are installed on the server. The response includes signing-capable
 * certificates filtered by [OperationsConfig.certificateAliases] (when set), plus any
 * per-token warnings and locked-token entries from the discovery process.
 *
 * Two certificate sources are merged:
 * - **Token discovery** — PKCS#11 and OS-store certificates via [ListCertificatesUseCase]. This is
 *   empty on a headless server with no hardware tokens.
 * - **File keystore** — when [OperationsConfig.signingKeystorePath] is configured, the certificate(s)
 *   in that PKCS#12 keystore (the server's own signing identity) are enumerated via
 *   [ListKeystoreCertificatesUseCase], using the password from `OMNISIGN_SIGNING_KEYSTORE_PASSWORD`.
 *   Without this, a file-keystore server would sign fine through `/api/v1/sign` yet expose no
 *   selectable certificate here, leaving remote clients (the web app) with an empty picker.
 *
 * Certificate discovery is not profile-scoped — available certificates depend only on the
 * server's hardware, software token, and file-keystore configuration, not on the active profile.
 * The [OperationsConfig.certificateAliases] allow-list is applied uniformly to the merged result.
 */
fun Route.certificateRoutes() {
	val listCertificatesUseCase by inject<ListCertificatesUseCase>()
	val listKeystoreCertificatesUseCase by inject<ListKeystoreCertificatesUseCase>()
	val serverConfig by inject<ServerConfig>()
	val secrets by inject<ServerSecrets>()

	get("/api/v1/certificates") {
		if (!call.requireOperation(AllowedOperation.SIGN, serverConfig)) return@get

		val discovery = listCertificatesUseCase(promptForLocked = false)
			.getOrElse { throw OperationException(it) }

		val keystorePath = serverConfig.operations.signingKeystorePath
		val keystoreCertificates = if (keystorePath != null) {
			listKeystoreCertificatesUseCase(keystorePath, secrets.signingKeystorePassword)
				.getOrElse { throw OperationException(it) }
		} else {
			emptyList()
		}

		val merged = discovery.copy(certificates = discovery.certificates + keystoreCertificates)
		val allowedAliases = serverConfig.operations.certificateAliases
		val response = if (allowedAliases != null) {
			merged.copy(certificates = merged.certificates.filter { it.alias in allowedAliases })
		} else {
			merged
		}
		call.respond(response)
	}
}
