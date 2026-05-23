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
 * Called from [cz.pizavo.omnisign.moduleWith] before any plugin that depends on the CORS
 * settings is installed. The validated [CorsConfig] is returned so the
 * [cz.pizavo.omnisign.plugins.configureCors] call can take a non-null argument and
 * skip its own null/empty handling.
 *
 * @param config Raw CORS configuration as parsed from `server.yml`, or `null` when the
 *   `cors:` block is absent entirely.
 * @return The validated [CorsConfig] (non-null, non-empty `allowedOrigins`).
 * @throws IllegalArgumentException with operator-actionable guidance when [config] is
 *   `null` or when [CorsConfig.allowedOrigins] is empty.
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
	return config
}
