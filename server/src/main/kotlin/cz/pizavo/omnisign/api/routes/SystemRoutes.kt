package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.HealthResponse
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Mount system routes: health check and server info.
 *
 * - `GET /api/v1/health` — returns [HealthResponse] for monitoring probes.
 */
fun Route.systemRoutes() {
	get("/api/v1/health") {
		call.respond(
			HealthResponse(
				version = javaClass.`package`?.implementationVersion ?: "dev",
			),
		)
	}
}

