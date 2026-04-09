package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.routes.*
import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.config.AuthConfig
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*

/**
 * Install all API route groups into the Ktor routing tree.
 *
 * When [authConfig] is non-null and [requireLogin] is `true`, all operational routes
 * (sign, validate, timestamp, certificates, config) are wrapped in an
 * `authenticate(jwt-api)` block that requires a valid JWT Bearer token.
 * The health check and all `/auth/\**` routes remain publicly accessible regardless.
 *
 * @param authConfig Root authentication configuration, or `null` when auth is disabled.
 * @param requireLogin Whether authenticated access to operational routes is enforced.
 */
fun Application.configureRouting(authConfig: AuthConfig? = null, requireLogin: Boolean = false) {
	routing {
		systemRoutes()
		authRoutes(authConfig)

		if (requireLogin && authConfig != null) {
			authenticate(JwtSessionService.AUTH_NAME_JWT) {
				configRoutes()
				certificateRoutes()
				signingRoutes()
				validationRoutes()
				timestampRoutes()
			}
		} else {
			configRoutes()
			certificateRoutes()
			signingRoutes()
			validationRoutes()
			timestampRoutes()
		}
	}
}

