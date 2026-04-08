package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.routes.*
import io.ktor.server.application.*
import io.ktor.server.routing.*

/**
 * Install all API route groups into the Ktor routing tree.
 */
fun Application.configureRouting() {
	routing {
		systemRoutes()
		configRoutes()
		certificateRoutes()
		signingRoutes()
		validationRoutes()
		timestampRoutes()
	}
}

