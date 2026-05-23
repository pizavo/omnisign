package cz.pizavo.omnisign.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Fetches user identity claims from an OIDC `/userinfo` endpoint or the GitHub user API
 * using the access token obtained during the OAuth2 authorization-code flow.
 *
 * Claims are returned as a raw [JsonObject] so that both standard principal fields and
 * provider-specific attributes (e.g. `schac_home_organization`, `eduperson_scoped_affiliation`)
 * are available for post-login filtering via [areRequiredClaimsSatisfied].
 *
 * @param httpClient Ktor [HttpClient] used for outbound HTTP requests.
 */
class OidcUserInfoService(private val httpClient: HttpClient) {

    /**
     * Fetch the raw claims [JsonObject] from [userInfoUrl] using the supplied [accessToken].
     *
     * @param userInfoUrl The IdP's UserInfo endpoint URL.
     * @param accessToken OAuth2 / OIDC access token.
     * @return Raw claims as a [JsonObject].
     */
    suspend fun fetchRawClaims(userInfoUrl: String, accessToken: String): JsonObject {
        logger.debug { "Fetching user info from $userInfoUrl" }
        return httpClient.get(userInfoUrl) {
            bearerAuth(accessToken)
            accept(ContentType.Application.Json)
        }.body()
    }

    /**
     * Map a raw claims [JsonObject] to an [AuthenticatedPrincipal] for the given provider.
     *
     * Subject resolution order: `sub` → `id` (GitHub numeric ID) → `login` (GitHub username).
     *   A subject is mandatory — it is the load-bearing identity for every downstream
     *   consumer (JWT subject, refresh-token lookup, M-3 id_token cross-check). When all
     *   three candidates are missing the principal cannot be constructed.
     * Email: `email` claim only; `null` when the IdP does not supply one. The previous
     *   `?: login` fallback was the L-6 footgun — it synthesised a non-email string
     *   (e.g. the GitHub login `"octocat"`) and threaded it into the JWT, the
     *   refresh-token store, and audit logs. Now missing email surfaces as `null` so
     *   downstream consumers can decide explicitly (see [AuthenticatedPrincipal.email]
     *   and [isEmailDomainAllowed]).
     * Display name resolution order: `name` → `preferred_username` → `login`.
     *
     * @param claims Raw claims from the IdP's UserInfo endpoint.
     * @param providerName SSO provider name that produced the claims.
     * @return Populated [AuthenticatedPrincipal]; [AuthenticatedPrincipal.email] may be
     *   `null` when the IdP does not supply an `email` claim.
     * @throws IllegalStateException if no usable subject claim is found.
     */
    fun toPrincipal(claims: JsonObject, providerName: String): AuthenticatedPrincipal {
        val userId = claims.string("sub")
            ?: claims.string("id")
            ?: claims.string("login")
            ?: error("No subject claim found in user-info response from provider '$providerName'")

        return AuthenticatedPrincipal(
            userId = userId,
            email = claims.string("email"),
            displayName = claims.string("name")
                ?: claims.string("preferred_username")
                ?: claims.string("login"),
            providerName = providerName,
            authTime = Clock.System.now(),
        )
    }
}

/**
 * Extract a string value from this [JsonObject] by [key], returning `null` when the key
 * is absent, the value is JSON `null`, or the element is not a primitive.
 */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
