package cz.pizavo.omnisign.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
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
 * are available for post-login filtering via [areRequiredClaimsSatisfied]. The service also
 * reports whether the IdP vouches for the user's address, which a concrete
 * `allowedEmailDomains` list requires (see [isEmailVerificationAccepted]): from the UserInfo
 * `email_verified` claim, or for GitHub, whose user API has no such claim, from the account's
 * email list.
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
     *   consumer (JWT subject, refresh-token lookup, the cross-check against the id_token's
     *   `sub`). When all three candidates are missing the principal cannot be constructed.
     * Email: `email` claim only; `null` when the IdP does not supply one. Nothing else stands
     *   in for it: a GitHub `login` such as `"octocat"` is no address, and passed off as one
     *   it would reach the JWT, the refresh-token store, and the logs. Downstream consumers
     *   decide explicitly what a missing address means (see [AuthenticatedPrincipal.email]
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

    /**
     * Read whether the UserInfo [claims] mark their `email` verified.
     *
     * Accepts the standard JSON boolean as well as the strings `"true"` and `"false"` in any
     * case, which Amazon Cognito's UserInfo endpoint returns.
     *
     * @param claims Raw claims from the IdP's UserInfo endpoint.
     * @return The `email_verified` flag, or `null` when the claims carry none.
     */
    fun emailVerified(claims: JsonObject): Boolean? = claims.verificationFlag("email_verified")

    /**
     * Ask GitHub whether [email] is one of the account's verified addresses.
     *
     * GitHub's user API reports only the public profile address, with no verification flag.
     * The account's email list at [GITHUB_USER_EMAILS_API_URL] carries one for each address;
     * reading it needs the `user:email` scope.
     *
     * @param accessToken The GitHub OAuth access token of the login.
     * @param email The address the login is judged by.
     * @return The `verified` flag of the listed address that equals [email] ignoring case, or
     *   `null` when the list does not include it or cannot be read — no scope, an error
     *   status, a network failure, or an unexpected body, each logged.
     */
    suspend fun fetchGitHubEmailVerified(accessToken: String, email: String): Boolean? {
        val entries = try {
            val response = httpClient.get(GITHUB_USER_EMAILS_API_URL) {
                bearerAuth(accessToken)
                accept(ContentType.Application.Json)
            }
            if (!response.status.isSuccess()) {
                logger.warn {
                    "GitHub refused the account's email list (HTTP ${response.status.value}), so the " +
                        "address cannot be checked as verified. Request the user:email scope."
                }
                return null
            }
            response.body<JsonArray>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Could not read the GitHub account's email list" }
            return null
        }
        return entries
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.string("email").equals(email, ignoreCase = true) }
            ?.verificationFlag("verified")
    }

    companion object {
        /** GitHub's list of the authenticated account's addresses, each with a `verified` flag. */
        const val GITHUB_USER_EMAILS_API_URL = "https://api.github.com/user/emails"
    }
}

/**
 * Extract a string value from this [JsonObject] by [key], returning `null` when the key
 * is absent, the value is JSON `null`, or the element is not a primitive.
 */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Read the verification flag under [key]: a JSON boolean, or the string `"true"` or
 * `"false"` in any case.
 *
 * @return The flag, or `null` when the key is absent or holds anything else.
 */
private fun JsonObject.verificationFlag(key: String): Boolean? =
    string(key)?.lowercase()?.toBooleanStrictOrNull()
