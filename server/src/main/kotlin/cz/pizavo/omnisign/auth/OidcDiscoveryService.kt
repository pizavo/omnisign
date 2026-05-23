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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Cached OIDC discovery document plus the wall-clock moment it was fetched from the IdP.
 *
 * The TTL check in [OidcDiscoveryService.discover] compares `now - cachedAt` against
 * [OidcDiscoveryService.DISCOVERY_CACHE_TTL] to decide whether to refetch.
 */
private data class CachedDiscovery(
    val document: OidcDiscoveryDocument,
    val cachedAt: Instant,
)

/**
 * Minimal OIDC Discovery Document as defined in
 * [OpenID Connect Discovery 1.0](https://openid.net/specs/openid-connect-discovery-1_0.html).
 *
 * Only the fields required for the authorization-code flow are captured here.
 *
 * @property issuer The IdP's canonical issuer URL. Used by [IdTokenVerifier] as the
 *   expected value of the id_token `iss` claim — OIDC requires the token's issuer to
 *   match the discovery document's issuer exactly.
 * @property authorizationEndpoint URL of the authorization endpoint.
 * @property tokenEndpoint URL of the token endpoint.
 * @property userInfoEndpoint URL of the UserInfo endpoint.
 * @property jwksUri URL of the JSON Web Key Set, fetched by [IdTokenVerifier] to resolve
 *   the public key used to sign the id_token.
 */
@Serializable
data class OidcDiscoveryDocument(
    @SerialName("issuer") val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("userinfo_endpoint") val userInfoEndpoint: String? = null,
    @SerialName("jwks_uri") val jwksUri: String,
)

/**
 * Fetches and caches OIDC discovery documents for configured providers.
 *
 * Per-provider entries are cached in memory for [DISCOVERY_CACHE_TTL] (24 hours). On a
 * cache miss or after the TTL elapses, the document is refetched from the IdP. The
 * cache is keyed on [OidcProviderConfig.name] (provider names are required to be unique
 * across providers).
 *
 * **Stale-on-error fallback.** If a refresh attempt fails (network error, IdP
 * returning a non-2xx response, response parsing failure), the previously-cached
 * document is served and a warning is logged. This trades strict freshness for
 * availability — a transient IdP outage does not block all logins; if the operator
 * has rotated the discovery URL contents and the IdP is down at the moment we try
 * to fetch, we keep using the last-known-good config until the next successful
 * refresh. A failure on the **first** fetch (no prior cached entry to fall back to)
 * propagates as an exception so the operator sees the issue immediately.
 *
 * @param httpClient Ktor [HttpClient] used for outbound HTTP requests. The
 *   per-request timeout configured on this client (see
 *   [cz.pizavo.omnisign.di.serverModule]) bounds how long a refresh attempt can
 *   block before the stale-on-error fallback takes over.
 * @param clock Wall-clock source for TTL bookkeeping. Tests override with a fake
 *   clock to exercise expiry behavior without sleeping; production uses
 *   [Clock.System].
 */
class OidcDiscoveryService(
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.System,
) {

    private val cacheMutex = Mutex()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedDiscovery>()

    /**
     * Resolve and return the OIDC discovery document for [provider].
     *
     * Cache flow: fast-path returns the in-memory cached entry when it is younger
     * than [DISCOVERY_CACHE_TTL]. Otherwise acquires the per-service mutex,
     * re-checks the cache (in case another coroutine has just refreshed it), and
     * refetches the document from the IdP. On refetch failure with a prior cached
     * entry the stale entry is served and a warning logged; otherwise the failure
     * propagates.
     *
     * @param provider OIDC provider configuration.
     * @return Parsed [OidcDiscoveryDocument].
     * @throws IllegalStateException if the discovery URL cannot be determined.
     * @throws Exception on a fetch failure when no prior cached entry exists.
     */
    suspend fun discover(provider: OidcProviderConfig): OidcDiscoveryDocument {
        val cached = cache[provider.name]
        if (cached != null && isFresh(cached)) {
            return cached.document
        }

        return cacheMutex.withLock {
            val current = cache[provider.name]
            if (current != null && isFresh(current)) {
                return@withLock current.document
            }

            val url = resolveDiscoveryUrl(provider)
            val verb = if (current == null) "Fetching" else "Refreshing"
            logger.info { "$verb OIDC discovery document for '${provider.name}' from $url" }

            try {
                val fresh: OidcDiscoveryDocument = httpClient.get(url).body()
                cache[provider.name] = CachedDiscovery(fresh, clock.now())
                fresh
            } catch (e: Exception) {
                if (current != null) {
                    logger.warn(e) {
                        "Failed to refresh OIDC discovery document for '${provider.name}' " +
                            "(cached at ${current.cachedAt}); serving last-known-good entry " +
                            "and will retry on the next cache miss"
                    }
                    current.document
                } else {
                    throw e
                }
            }
        }
    }

    /** Whether [entry] is still within the [DISCOVERY_CACHE_TTL] window from now. */
    private fun isFresh(entry: CachedDiscovery): Boolean =
        clock.now() - entry.cachedAt < DISCOVERY_CACHE_TTL

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

        /**
         * Time-to-live for a cached OIDC discovery entry.
         *
         * 24 hours is the conventional value for OIDC discovery caching (the analysis
         * doc's recommendation, and the value used by several reference implementations
         * including Spring Security and oidc-client-js). Discovery documents change
         * rarely in practice — endpoint URLs and `jwks_uri` are typically stable for
         * the lifetime of the IdP deployment — so a daily refresh is sufficient to
         * pick up rotation events without imposing meaningful per-request overhead.
         *
         * JWS signing-key rotation is handled separately by [IdTokenVerifier]'s
         * `JwkProviderBuilder` cache (1 hour TTL on the JWKS itself), so a 24-hour TTL
         * here does not delay key-rotation pickup.
         */
        val DISCOVERY_CACHE_TTL: Duration = 24.hours
    }
}

