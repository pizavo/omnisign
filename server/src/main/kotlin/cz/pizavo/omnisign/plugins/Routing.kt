package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.routes.extensionRoutes
import cz.pizavo.omnisign.api.routes.signingRoutes
import cz.pizavo.omnisign.api.routes.systemRoutes
import cz.pizavo.omnisign.api.routes.validationRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

/**
 * Install all API route groups into the Ktor routing tree.
 */
fun Application.configureRouting() {
	routing {
		systemRoutes()
		signingRoutes()
		validationRoutes()
		extensionRoutes()
	}
}

