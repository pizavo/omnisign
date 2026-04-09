package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * Minimal OIDC Discovery Document as defined in
 * [OpenID Connect Discovery 1.0](https://openid.net/specs/openid-connect-discovery-1_0.html).
 *
 * Only the fields required for the authorization-code flow are captured here.
 *
 * @property authorizationEndpoint URL of the authorization endpoint.
 * @property tokenEndpoint URL of the token endpoint.
 * @property userInfoEndpoint URL of the UserInfo endpoint.
 * @property jwksUri URL of the JSON Web Key Set (used for token signature verification by Ktor).
 */
@Serializable
data class OidcDiscoveryDocument(
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("userinfo_endpoint") val userInfoEndpoint: String? = null,
    @SerialName("jwks_uri") val jwksUri: String,
)

/**
 * Fetches and caches OIDC discovery documents for configured providers.
 *
 * The discovery document is fetched once on first access and cached in memory for the
 * lifetime of the server process. Failures are propagated as [IllegalStateException] so
 * the server start-up can surface them clearly.
 *
 * @param httpClient Ktor [HttpClient] used for outbound HTTP requests.
 */
class OidcDiscoveryService(private val httpClient: HttpClient) {

    private val cacheMutex = Mutex()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, OidcDiscoveryDocument>()

    /**
     * Resolve and return the OIDC discovery document for [provider].
     *
     * On the first call for a given provider, the document is fetched from the IdP.
     * Subsequent calls return the cached value.
     *
     * @param provider OIDC provider configuration.
     * @return Parsed [OidcDiscoveryDocument].
     * @throws IllegalStateException if the discovery URL cannot be determined or the
     *   fetch fails.
     */
    suspend fun discover(provider: OidcProviderConfig): OidcDiscoveryDocument {
        cache[provider.name]?.let { return it }
        return cacheMutex.withLock {
            cache.getOrPut(provider.name) {
                val url = resolveDiscoveryUrl(provider)
                logger.info { "Fetching OIDC discovery document for '${provider.name}' from $url" }
                httpClient.get(url).body()
            }
        }
    }

    /**
     * Resolve the effective discovery document URL for [provider], applying any preset
     * templates and tenant substitutions.
     */
    private fun resolveDiscoveryUrl(provider: OidcProviderConfig): String {
        provider.discoveryUrl?.let { return it }

        val preset = provider.preset
            ?: throw IllegalStateException(
                "OIDC provider '${provider.name}' has no discoveryUrl and no preset configured",
            )

        if (preset.requiresManualUrls) {
            return resolveGithubUrls()
        }

        val template = preset.discoveryUrlTemplate
            ?: return preset.discoveryUrl
                ?: throw IllegalStateException(
                    "Preset ${preset.name} has neither a static discoveryUrl nor a template",
                )

        return applyTemplate(template, preset, provider.tenantId)
    }

    /**
     * GitHub does not have an OIDC discovery document — this returns a placeholder
     * that signals the auth plugin to use hard-coded GitHub endpoints.
     */
    private fun resolveGithubUrls(): String = GITHUB_PSEUDO_DISCOVERY_URL

    /**
     * Apply tenant / realm / domain substitution to a discovery URL template.
     */
    private fun applyTemplate(
        template: String,
        preset: SsoProviderPreset,
        tenantId: String?,
    ): String {
        if (tenantId == null) {
            throw IllegalStateException(
                "Preset ${preset.name} requires a tenantId to resolve the discovery URL",
            )
        }

        return when (preset) {
            SsoProviderPreset.MICROSOFT ->
                template.replace("{tenant}", tenantId)

            SsoProviderPreset.AMAZON_COGNITO -> {
                val parts = tenantId.split("/", limit = 2)
                require(parts.size == 2) {
                    "Amazon Cognito tenantId must be '{region}/{poolId}', got: $tenantId"
                }
                template.replace("{region}", parts[0]).replace("{poolId}", parts[1])
            }

            SsoProviderPreset.KEYCLOAK -> {
                val parts = tenantId.split("/", limit = 2)
                require(parts.size == 2) {
                    "Keycloak tenantId must be '{host}/{realm}', got: $tenantId"
                }
                template.replace("{host}", parts[0]).replace("{realm}", parts[1])
            }

            SsoProviderPreset.AUTH0 ->
                template.replace("{domain}", tenantId)

            else -> template
        }
    }

    companion object {
        /**
         * Sentinel URL returned when the GitHub preset is used; the auth plugin
         * substitutes hard-coded GitHub endpoints in this case.
         */
        const val GITHUB_PSEUDO_DISCOVERY_URL = "github://pseudo-discovery"

        /** GitHub authorization endpoint. */
        const val GITHUB_AUTHORIZATION_URL = "https://github.com/login/oauth/authorize"

        /** GitHub token endpoint. */
        const val GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token"

        /** GitHub user-info API endpoint. */
        const val GITHUB_USER_API_URL = "https://api.github.com/user"
    }
}

