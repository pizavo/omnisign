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
 * When [authConfig] is non-null and [AuthConfig.enabled] is `true`, all operational routes
 * (sign, validate, timestamp, certificates, config) are wrapped in an
 * `authenticate(jwt-api)` block that requires a valid JWT Bearer token.
 * The health check and all `/auth/​**` routes remain publicly accessible regardless.
 *
 * @param authConfig Root authentication configuration, or `null` when auth is disabled.
 */
fun Application.configureRouting(authConfig: AuthConfig? = null) {
	routing {
		systemRoutes()
		authRoutes(authConfig)

		if (authConfig?.enabled == true) {
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

