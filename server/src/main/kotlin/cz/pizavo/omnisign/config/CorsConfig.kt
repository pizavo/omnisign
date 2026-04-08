package cz.pizavo.omnisign.config

/**
 * CORS policy configuration.
 *
 * @property allowedOrigins Origins permitted to access the API. An empty list disables CORS.
 *   The special value `*` allows any origin.
 * @property allowCredentials Whether `Access-Control-Allow-Credentials` is sent.
 */
data class CorsConfig(
	val allowedOrigins: List<String> = emptyList(),
	val allowCredentials: Boolean = false,
)

