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
 * @property allowedOrigins Required, non-empty list of origins permitted to access the
 *   API. Operators must always make a deliberate, explicit choice — there is no
 *   implicit "no-CORS" default, because for a web-facing API "didn't think about it",
 *   "explicitly allow all", and "explicitly deny all" must not collapse into the same
 *   runtime outcome (silent disable). Supported forms per entry:
 *   - `"*"` — explicit allow-all (parallel to
 *     [OidcProviderConfig.allowedEmailDomains]'s `["*"]`); accept any origin. Use this
 *     when the API is intentionally public, or for local development where the dev
 *     server's port shifts.
 *   - Bare host (`"example.com"`) — accept any scheme/port for that host.
 *   - Host:port (`"example.com:50443"`).
 *   - Full URL with scheme (`"https://example.com:50443"`). The plugin strips
 *     `http(s)://` prefixes so all three forms are interchangeable.
 *
 *   Empty list and missing `cors:` block are both rejected at server startup
 *   ([validateCorsConfig]).
 */
data class CorsConfig(
	val allowedOrigins: List<String> = emptyList(),
)

