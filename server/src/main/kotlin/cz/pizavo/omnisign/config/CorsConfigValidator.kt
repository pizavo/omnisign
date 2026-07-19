package cz.pizavo.omnisign.config

/**
 * Validate operator-supplied CORS configuration at server startup.
 *
 * Closes a fail-open silent-disable path: without this check, three distinct operator
 * intents collapse into the same runtime behavior of "no CORS installed at all" —
 *
 * 1. Operator never set `cors:` (didn't read that part of the YAML).
 * 2. Operator set `cors: { allowedOrigins: [] }` (thought they were "blocking
 *    everything", actually disabled CORS — same fail-open pattern as
 *    [validateAuthConfig]'s rejection of empty `allowedEmailDomains`).
 * 3. Operator commented out the `cors:` block to debug a typo and forgot to uncomment.
 *
 * After this validator, the only ways to start the server are with an explicit list of
 * allowed origins or the explicit `["*"]` wildcard. Both decisions are deliberate;
 * neither is the silent-disable path. The shipped `server.yml` carries an uncommented
 * `cors:` block listing the common local-development origins so the default workflow
 * passes without operator action.
 *
 * Separately, closes a fail-open *combination*: `allowedOrigins: ["*"]` together with
 * `allowCredentials: true`. Each is a reasonable setting alone, which is what makes the
 * pair dangerous — an operator who enables credentials for the web app and leaves the
 * development wildcard in place reads the result as "allow any origin, and allow cookies",
 * but gets "let every site the user visits make authenticated requests as them and read
 * the replies". The mechanism is Ktor's [anyHost][io.ktor.server.plugins.cors.CORSConfig.anyHost]:
 * with credentials enabled it reflects the request's `Origin` header into
 * `Access-Control-Allow-Origin` instead of emitting a literal `*`, and browsers accept a
 * reflected origin where they would reject the wildcard. The Fetch spec's own
 * wildcard-plus-credentials ban is therefore not enforced anywhere in this path, so it is
 * enforced here — at startup, where an operator can act on it, rather than as a silent
 * runtime property nobody observes.
 *
 * Called from [cz.pizavo.omnisign.moduleWith] before any plugin that depends on the CORS
 * settings is installed. The validated [CorsConfig] is returned so the
 * [cz.pizavo.omnisign.plugins.configureCors] call can take a non-null argument and
 * skip its own null/empty handling.
 *
 * @param config Raw CORS configuration as parsed from `server.yml`, or `null` when the
 *   `cors:` block is absent entirely.
 * @return The validated [CorsConfig] (non-null, non-empty `allowedOrigins`, and not the
 *   wildcard-with-credentials combination).
 * @throws IllegalArgumentException with operator-actionable guidance when [config] is
 *   `null`, when [CorsConfig.allowedOrigins] is empty, or when [CorsConfig.allowCredentials]
 *   is combined with the `["*"]` wildcard.
 */
fun validateCorsConfig(config: CorsConfig?): CorsConfig {
	requireNotNull(config) {
		"CORS is not configured. Set cors.allowedOrigins in server.yml to either a list " +
			"of explicit origins (e.g. [\"https://omnisign.example.com\"]) or [\"*\"] " +
			"to allow any origin."
	}
	require(config.allowedOrigins.isNotEmpty()) {
		"cors.allowedOrigins must not be empty. Use [\"*\"] to allow any origin, or list " +
			"specific origins (host, host:port, or http(s)://host[:port])."
	}
	require(!(config.allowCredentials && WILDCARD_ORIGIN in config.allowedOrigins)) {
		"cors.allowCredentials: true cannot be combined with cors.allowedOrigins: " +
			"[\"$WILDCARD_ORIGIN\"] — the wildcard is reflected back as the caller's own " +
			"origin once credentials are enabled, which would let any site the user visits " +
			"make authenticated requests to this server and read the responses. List the " +
			"origins your browser front-end is served from (e.g. " +
			"[\"https://omnisign.example.com\"]), or set cors.allowCredentials: false if no " +
			"browser front-end uses the refresh cookie."
	}
	return config
}

/**
 * The explicit allow-all entry accepted in [CorsConfig.allowedOrigins].
 */
private const val WILDCARD_ORIGIN = "*"
