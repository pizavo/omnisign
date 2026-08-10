package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.api.model.responses.CreditsResponse
import cz.pizavo.omnisign.api.model.responses.HealthResponse
import cz.pizavo.omnisign.auth.AuthenticatedPrincipal
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.legal.ThirdPartyCreditsReader
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/** Surface tag under which the generated credits list records what the server distributes. */
private const val SERVER_SURFACE = "server"

/**
 * Mount system routes: health check, server info, capabilities discovery, and third-party credits.
 *
 * All three routes are always public regardless of authentication configuration.
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
 * - `GET /api/v1/credits` — returns [CreditsResponse] listing every third-party component this
 *   server distributes, with OmniSign's own licence and source location. The desktop and web
 *   apps show that list in a dialog; a server has no interface to show it in, so this endpoint
 *   is how a network user obtains the attributions the weak-copyleft licences require to travel
 *   with the work, and the offer of source the GNU AGPL extends to them. Deliberately not gated
 *   behind authentication, since a duty owed to whoever interacts with the deployment cannot be
 *   conditioned on having an account.
 *
 * The health and capability responses also carry the deploy-time branding: the operator's optional
 * `organizationName` (from [ServerConfig.organizationName]) and the fixed `poweredBy` OmniSign
 * attribution. The label is normalized (blank → absent) once here and shared by both routes so the
 * two can never drift, letting an API-only deployment surface its identity without a web frontend.
 */
fun Route.systemRoutes() {
	val serverConfig by inject<ServerConfig>()
	val configRepository by inject<ConfigRepository>()
	val creditsReader by inject<ThirdPartyCreditsReader>()
	val organizationName = serverConfig.organizationName?.takeIf { it.isNotBlank() }

	get("/api/v1/health") {
		call.respond(
			HealthResponse(
				version = javaClass.`package`?.implementationVersion ?: "dev",
				organizationName = organizationName,
			),
		)
	}

	get("/api/v1/capabilities") {
		val authEnabled = serverConfig.auth?.enabled == true
		val isAuthenticated = call.principal<AuthenticatedPrincipal>() != null
		val appConfig = configRepository.getCurrentConfig()
		call.respond(
			CapabilitiesResponse(
				allowedOperations = serverConfig.operations.allowed.map { it.name },
				profiles = if (authEnabled && !isAuthenticated) emptyList() else appConfig.profiles.keys.toList(),
				maxFileSize = serverConfig.maxFileSize,
				authEnabled = authEnabled,
				organizationName = organizationName,
			),
		)
	}

	get("/api/v1/credits") {
		call.respond(CreditsResponse(components = creditsReader.read(SERVER_SURFACE)))
	}
}

