package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.api.model.responses.HealthResponse
import cz.pizavo.omnisign.auth.AuthenticatedPrincipal
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount system routes: health check, server info, and capabilities discovery.
 *
 * Both routes are always public regardless of authentication configuration.
 *
 * - `GET /api/v1/health` — returns [HealthResponse] for monitoring probes.
 * - `GET /api/v1/capabilities` — returns [CapabilitiesResponse] describing enabled
 *   operations, available profiles, and upload limits. When
 *   [cz.pizavo.omnisign.config.AuthConfig.enabled] is `true` and the caller has no valid
 *   JWT token, the `profiles` field is returned as an empty list to avoid leaking internal
 *   profile names to unauthenticated callers. Authenticated callers receive the full list.
 *   The `authEnabled` field signals the frontend to redirect to login. This route is
 *   wrapped in an optional `authenticate` scope so the principal is available when a valid
 *   token is supplied, but requests without a token are not rejected.
 */
fun Route.systemRoutes() {
	val serverConfig by inject<ServerConfig>()
	val configRepository by inject<ConfigRepository>()

	get("/api/v1/health") {
		call.respond(
			HealthResponse(
				version = javaClass.`package`?.implementationVersion ?: "dev",
			),
		)
	}

	get("/api/v1/capabilities") {
		val authEnabled = serverConfig.auth?.enabled == true
		val isAuthenticated = call.principal<AuthenticatedPrincipal>() != null
		val appConfig = configRepository.getCurrentConfig()
		call.respond(
			CapabilitiesResponse(
				allowedOperations = serverConfig.allowedOperations.map { it.name },
				profiles = if (authEnabled && !isAuthenticated) emptyList() else appConfig.profiles.keys.toList(),
				maxFileSize = serverConfig.maxFileSize,
				authEnabled = authEnabled,
			),
		)
	}
}

