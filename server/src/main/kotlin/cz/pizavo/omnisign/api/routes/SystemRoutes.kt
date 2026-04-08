package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.api.model.responses.HealthResponse
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount system routes: health check, server info, and capabilities discovery.
 *
 * - `GET /api/v1/health` — returns [HealthResponse] for monitoring probes.
 * - `GET /api/v1/capabilities` — returns [CapabilitiesResponse] describing enabled
 *   operations, available profiles, and upload limits.
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
		val appConfig = configRepository.getCurrentConfig()
		call.respond(
			CapabilitiesResponse(
				allowedOperations = serverConfig.allowedOperations.map { it.name },
				profiles = appConfig.profiles.keys.toList(),
				maxFileSize = serverConfig.maxFileSize,
			),
		)
	}
}

