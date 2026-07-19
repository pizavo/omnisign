package cz.pizavo.omnisign.config

/**
 * CORS policy configuration.
 *
 * @property allowCredentials Whether cross-origin requests may carry credentials (cookies)
 *   and, correspondingly, whether the browser is allowed to read the response. Defaults to
 *   `false`, which is right for every deployment whose clients authenticate with a JWT
 *   bearer token: the Fetch spec treats `Authorization` as an ordinary request header
 *   rather than a credential, so bearer-token CORS needs no opt-in.
 *
 *   Set it to `true` only to serve a browser front-end that keeps its session in the
 *   refresh cookie — the OmniSign web app on a different origin to the API, which is the
 *   usual split (`app.example.com` and `api.example.com`, or one host with the API on a
 *   second port). Without it the browser withholds the cookie from `/auth/refresh`, and the
 *   app falls back to a full round-trip through the identity provider on every page load;
 *   it still works, just less quietly.
 *
 *   `true` is incompatible with the `["*"]` wildcard and [validateCorsConfig] rejects the
 *   pair at startup. The two look combinable but are not: Ktor's `anyHost()` does not emit
 *   a literal `*` when credentials are enabled, it **reflects the caller's `Origin` header**
 *   back as `Access-Control-Allow-Origin`, and a reflected origin paired with
 *   `Access-Control-Allow-Credentials: true` is exactly the pattern browsers honour — so
 *   any site the user visits could issue credentialed requests and read the authenticated
 *   responses. The wildcard's "I have not thought about origins" meaning is safe on its own
 *   and unsafe here, so the combination is refused rather than silently reinterpreted.
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
 *   - Host:port (`"example.com:18443"`).
 *   - Full URL with scheme (`"https://example.com:18443"`). The plugin strips
 *     `http(s)://` prefixes so all three forms are interchangeable.
 *
 *   Empty list and missing `cors:` block are both rejected at server startup
 *   ([validateCorsConfig]).
 */
data class CorsConfig(
	val allowedOrigins: List<String> = emptyList(),
	val allowCredentials: Boolean = false,
)

