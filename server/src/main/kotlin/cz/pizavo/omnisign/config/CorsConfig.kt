package cz.pizavo.omnisign.config

/**
 * CORS policy configuration.
 *
 * No `allowCredentials` field by design. JWT bearer tokens are sent in the `Authorization`
 * header (already in the allowlist), which the Fetch spec treats as a regular request
 * header rather than a credential — so credentialed-CORS opt-in is not needed for the
 * current auth model. If/when OmniSign migrates to cookie-based sessions the field must
 * be re-added together with a startup guard that rejects the `["*"]` + credentials
 * combination (a CORS misconfiguration that, via Ktor's origin-reflecting `anyHost()`,
 * would otherwise let any origin read authenticated responses).
 *
 * @property allowedOrigins Origins permitted to access the API. An empty list disables CORS.
 *   The special value `*` allows any origin.
 */
data class CorsConfig(
	val allowedOrigins: List<String> = emptyList(),
)

