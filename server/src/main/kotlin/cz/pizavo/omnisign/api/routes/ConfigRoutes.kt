package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.model.responses.toResponse
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount configuration read-only routes under `/api/v1/config`.
 *
 * All four endpoints are open — no [cz.pizavo.omnisign.config.AllowedOperation] guard —
 * because they expose only sanitized metadata (credentials stripped) that the Wasm frontend
 * needs to populate its UI. If [cz.pizavo.omnisign.config.ServerConfig.requireLogin] is
 * enforced in the future these routes must be placed behind the same authentication check.
 *
 * None of the endpoints use [cz.pizavo.omnisign.domain.model.config.AppConfig.activeProfile]
 * as a fallback. Profile selection is always explicit: callers must supply a `profile` query
 * parameter if they want profile-specific behaviour, otherwise global defaults apply.
 *
 * - `GET /api/v1/config/global` — returns [cz.pizavo.omnisign.api.model.responses.GlobalConfigResponse].
 * - `GET /api/v1/config/profiles` — returns a sorted list of [cz.pizavo.omnisign.api.model.responses.ProfileConfigResponse].
 * - `GET /api/v1/config/profiles/{name}` — returns a single [cz.pizavo.omnisign.api.model.responses.ProfileConfigResponse] or `404`.
 * - `GET /api/v1/config/resolved?profile={name}` — returns [cz.pizavo.omnisign.api.model.responses.ResolvedConfigResponse]
 *   for the given profile (or global defaults when `profile` is omitted), or `404` / `422` on error.
 */
fun Route.configRoutes() {
	val configRepository by inject<ConfigRepository>()

	get("/api/v1/config/global") {
		val appConfig = configRepository.getCurrentConfig()
		call.respond(appConfig.global.toResponse())
	}

	get("/api/v1/config/profiles") {
		val appConfig = configRepository.getCurrentConfig()
		val profiles = appConfig.profiles.values.sortedBy { it.name }.map { it.toResponse() }
		call.respond(profiles)
	}

	get("/api/v1/config/profiles/{name}") {
		val name = call.parameters["name"]
		if (name == null) {
			call.respond(
				HttpStatusCode.BadRequest,
				ApiError(error = "MISSING_PARAMETER", message = "Profile name path segment is required"),
			)
			return@get
		}
		val appConfig = configRepository.getCurrentConfig()
		val profile = appConfig.profiles[name]
		if (profile == null) {
			call.respond(
				HttpStatusCode.NotFound,
				ApiError(error = "PROFILE_NOT_FOUND", message = "Profile '$name' does not exist"),
			)
			return@get
		}
		call.respond(profile.toResponse())
	}

	get("/api/v1/config/resolved") {
		val profileName = call.request.queryParameters["profile"]
		val appConfig = configRepository.getCurrentConfig()

		if (profileName != null && !appConfig.profiles.containsKey(profileName)) {
			call.respond(
				HttpStatusCode.NotFound,
				ApiError(error = "PROFILE_NOT_FOUND", message = "Profile '$profileName' does not exist"),
			)
			return@get
		}

		val profileConfig = profileName?.let { appConfig.profiles[it] }

		ResolvedConfig.resolve(appConfig.global, profileConfig, null).fold(
			ifLeft = { error ->
				call.respond(
					HttpStatusCode.UnprocessableEntity,
					ApiError(error = "INVALID_CONFIGURATION", message = error.message),
				)
			},
			ifRight = { resolved ->
				call.respond(resolved.toResponse(profileName))
			},
		)
	}
}

