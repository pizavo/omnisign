package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.CorsConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

/**
 * Install Ktor [CORS] plugin driven by the [CorsConfig] from `server.yml`.
 *
 * Validation of [config] happens upstream in
 * [cz.pizavo.omnisign.config.validateCorsConfig], which guarantees the value passed here
 * is non-null and carries a non-empty `allowedOrigins` list. The null/empty
 * short-circuit that used to live in this function was a silent-disable footgun (three
 * distinct operator intents collapsed into the same runtime behavior); the validator
 * replaces it with an explicit startup failure.
 *
 * When [tlsEnabled] is `true` (direct TLS or behind a TLS-terminating reverse proxy),
 * only the `https` scheme is permitted for allowed hosts. Otherwise, both `http` and
 * `https` are accepted.
 *
 * The following request headers are allowed: `Content-Type`, `Authorization`, `X-Request-Id`.
 * The following response headers are exposed to JavaScript: `X-OmniSign-Result`, `X-Request-Id`.
 *
 * @param config CORS configuration that has already passed
 *   [cz.pizavo.omnisign.config.validateCorsConfig].
 * @param tlsEnabled Whether TLS is active, either directly or via a reverse proxy.
 */
fun Application.configureCors(config: CorsConfig, tlsEnabled: Boolean = false) {
	val schemes = if (tlsEnabled) listOf("https") else listOf("https", "http")

	install(CORS) {
		config.allowedOrigins.forEach { origin ->
			if (origin == "*") {
				anyHost()
			} else {
				allowHost(origin.removePrefix("https://").removePrefix("http://"), schemes = schemes)
			}
		}

		allowMethod(HttpMethod.Post)
		allowMethod(HttpMethod.Get)

		allowHeader(HttpHeaders.ContentType)
		allowHeader(HttpHeaders.Authorization)
		allowHeader(HttpHeaders.XRequestId)

		exposeHeader("X-OmniSign-Result")
		exposeHeader(HttpHeaders.XRequestId)
	}
}

