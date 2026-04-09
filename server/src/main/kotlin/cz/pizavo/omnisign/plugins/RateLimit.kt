package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.RateLimitConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.seconds

/** Named rate limit zone applied to all `/auth/​**` routes. */
val AUTH_RATE_LIMIT_NAME = RateLimitName("auth")

/** Named rate limit zone applied to all operational API routes. */
val API_RATE_LIMIT_NAME = RateLimitName("api")

/**
 * Install Ktor's [RateLimit] plugin with two named zones when [config] is non-null.
 *
 * - [AUTH_RATE_LIMIT_NAME] (`auth`) — applied to `/auth/​**` routes. Configured via
 *   [RateLimitConfig.auth].
 * - [API_RATE_LIMIT_NAME] (`api`) — applied to all operational API routes. Configured
 *   via [RateLimitConfig.api].
 *
 * When [config] is `null` the plugin is not installed and rate limiting is disabled.
 * Clients that exceed their quota receive `429 Too Many Requests` with standard
 * `Retry-After` and `X-RateLimit-*` response headers.
 *
 * @param config Rate limit configuration, or `null` to disable.
 */
fun Application.configureRateLimiting(config: RateLimitConfig?) {
	if (config == null) return

	install(RateLimit) {
		register(AUTH_RATE_LIMIT_NAME) {
			rateLimiter(limit = config.auth.limit, refillPeriod = config.auth.refillPeriodSeconds.seconds)
			requestKey { call -> call.request.origin.remoteAddress }
		}
		register(API_RATE_LIMIT_NAME) {
			rateLimiter(limit = config.api.limit, refillPeriod = config.api.refillPeriodSeconds.seconds)
			requestKey { call -> call.request.origin.remoteAddress }
		}
	}
}

/**
 * Wrap [block] in a [rateLimit] scope using [name] only when [enabled] is `true`.
 *
 * This avoids scattering `if (rateLimitEnabled)` conditionals throughout routing
 * code. When disabled, [block] is registered directly on the current [Route].
 *
 * @param name The rate limit zone name registered via [configureRateLimiting].
 * @param enabled Whether to apply rate limiting; must be `true` only when the
 *   [RateLimit] plugin has been installed.
 * @param block Route builder lambda.
 */
fun Route.rateLimitedIf(name: RateLimitName, enabled: Boolean, block: Route.() -> Unit) {
	if (enabled) rateLimit(name) { block() } else block()
}

