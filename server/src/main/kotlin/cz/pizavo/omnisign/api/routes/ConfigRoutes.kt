package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.model.responses.toResponse
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount configuration read-only routes under `/api/v1/config`.
 *
 * All four endpoints expose only sanitized metadata (credentials stripped) that the
 * Wasm frontend needs to populate its UI. They are grouped under the operational
 * `authenticate` block in [configureRouting][cz.pizavo.omnisign.plugins.configureRouting], so when
 * [AuthConfig.enabled][cz.pizavo.omnisign.config.AuthConfig.enabled] is `true` a valid JWT is required.
 *
 * None of the endpoints use [AppConfig.activeProfile][cz.pizavo.omnisign.domain.model.config.AppConfig.activeProfile]
 * as a fallback. Profile selection is always explicit: callers must supply a `profile` query
 * parameter if they want profile-specific behavior, otherwise global defaults apply.
 *
 * - `GET /api/v1/config/global` — returns [GlobalConfigResponse][cz.pizavo.omnisign.api.model.responses.GlobalConfigResponse].
 * - `GET /api/v1/config/profiles` — returns a sorted list of [ProfileConfigResponse][cz.pizavo.omnisign.api.model.responses.ProfileConfigResponse].
 * - `GET /api/v1/config/profiles/{name}` — returns a single [ProfileConfigResponse][cz.pizavo.omnisign.api.model.responses.ProfileConfigResponse] or `404`.
 * - `GET /api/v1/config/resolved?profile={name}` — returns [ResolvedConfigResponse][cz.pizavo.omnisign.api.model.responses.ResolvedConfigResponse]
 *   for the given profile (or global defaults when `profile` is omitted), or `404` / `422` on error.
 * - `GET /api/v1/config/trusted-certificates?profile={name}` — returns the directly-trusted
 *   certificates the server validates with in the requested scope (the named profile scope, or the
 *   global scope when `profile` is omitted), as a list of
 *   [TrustedCertificateResponse][cz.pizavo.omnisign.api.model.responses.TrustedCertificateResponse].
 *   Lets the web client display trust from the server's store, which it has no local copy of.
 */
fun Route.configRoutes() {
	val configRepository by inject<ConfigRepository>()
	val trustStore by inject<TrustStore>()

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

	get("/api/v1/config/trusted-certificates") {
		val profileName = call.request.queryParameters["profile"]
		trustStore.list(TrustScope.of(profileName)).fold(
			ifLeft = { error -> throw OperationException(error) },
			ifRight = { certificates -> call.respond(certificates.map { it.toResponse() }) },
		)
	}
}

