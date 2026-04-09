package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.routes.*
import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.RateLimitConfig
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*

/**
 * Install all API route groups into the Ktor routing tree.
 *
 * When [authConfig] is non-null and [AuthConfig.enabled] is `true`, all operational routes
 * (sign, validate, timestamp, certificates, config) are wrapped in an
 * `authenticate(jwt-api)` block that requires a valid JWT Bearer token.
 * The health check and all `/auth/\**` routes remain publicly accessible regardless.
 *
 * When [rateLimitConfig] is non-null (i.e. [configureRateLimiting] has been installed),
 * auth routes are wrapped in [AUTH_RATE_LIMIT_NAME] and operational routes in
 * [API_RATE_LIMIT_NAME]. System routes (health; capabilities) are not rate-limited.
 *
 * @param authConfig Root authentication configuration, or `null` when auth is disabled.
 * @param rateLimitConfig Rate limit configuration, or `null` when rate limiting is disabled.
 */
fun Application.configureRouting(authConfig: AuthConfig? = null, rateLimitConfig: RateLimitConfig? = null) {
	val rateLimitEnabled = rateLimitConfig != null
	routing {
		authenticate(JwtSessionService.AUTH_NAME_JWT, optional = true) {
			systemRoutes()
		}

		rateLimitedIf(AUTH_RATE_LIMIT_NAME, rateLimitEnabled) {
			authRoutes(authConfig)
		}

		rateLimitedIf(API_RATE_LIMIT_NAME, rateLimitEnabled) {
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
}

