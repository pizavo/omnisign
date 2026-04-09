package cz.pizavo.omnisign.config

/**
 * In-memory per-IP rate limiting configuration for the OmniSign server.
 *
 * Two independent limit zones are supported:
 * - [auth] — applied to all `/auth/​**` routes (login, callback). Should be kept low
 *   to prevent credential-stuffing and brute-force attacks.
 * - [api] — applied to all operational routes (validate, sign, timestamp, config,
 *   certificates). Should be set high enough for legitimate batch use-cases.
 *
 * Rate limiting is token-bucket-based: each IP receives [Zone.limit] tokens per
 * [Zone.refillPeriodSeconds] interval. Tokens are refilled at the end of each period.
 * Requests that exceed the bucket are rejected with HTTP `429 Too Many Requests`.
 *
 * **Proxy mode note:** The client IP is read from `call.request.origin.remoteAddress`.
 * When [ServerConfig.proxyMode] is `true`, Ktor's `XForwardedHeaders` plugin rewrites
 * this to the real client IP taken from the `X-Forwarded-For` header, so rate limiting
 * correctly tracks individual clients even behind a reverse proxy.
 *
 * @property auth Rate limit zone for authentication endpoints.
 * @property api Rate limit zone for operational API endpoints.
 */
data class RateLimitConfig(
	val auth: Zone = Zone(limit = 20, refillPeriodSeconds = 60),
	val api: Zone = Zone(limit = 200, refillPeriodSeconds = 60),
) {
	/**
	 * A single rate limit zone definition.
	 *
	 * @property limit Maximum number of requests allowed from a single IP within [refillPeriodSeconds].
	 * @property refillPeriodSeconds Duration of the token-bucket refill window in seconds.
	 */
	data class Zone(
		val limit: Int,
		val refillPeriodSeconds: Long,
	)
}


