package cz.pizavo.omnisign.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * PKCE (RFC 7636) protocol helper.
 *
 * On the authorization-code redirect step, [begin] generates a per-flow code verifier,
 * persists it via [PkceVerifierStore] keyed by the OAuth2 `state` parameter, and returns
 * the matching S256 challenge for inclusion in the authorize URL as
 * `code_challenge` + `code_challenge_method=S256`. On the callback step, [consume]
 * retrieves the verifier so the token-exchange POST can include it as `code_verifier`.
 *
 * Why PKCE for a confidential client like OmniSign: even though the `client_secret`
 * gates the token-exchange request, PKCE binds the authorization code to the entity
 * that originated the flow. An attacker who steals the code (server log, proxy log,
 * `Referer` header, browser history) AND somehow obtains the `client_secret` (env-var
 * dump, leaked deploy config) still cannot exchange the code without the verifier,
 * which never crosses the wire to anyone but us. RFC 9700 / OAuth 2.1 requires PKCE
 * for all clients regardless of confidentiality for this reason.
 *
 * @param store Backing store of `state → verifier` mappings. Concrete implementation
 *   typically [ExposedPkceVerifierStore].
 * @param ttl Per-flow lifetime of a stored verifier. Defaults to 5 minutes — long
 *   enough for a normal authorization-code flow including user MFA at the IdP, short
 *   enough that abandoned flows do not linger in the store.
 */
class PkceService(
    private val store: PkceVerifierStore,
    private val ttl: Duration = 5.minutes,
) {

    /**
     * Generate a fresh PKCE code verifier for [state], persist it via the backing store,
     * and return the matching S256 challenge for the authorize URL.
     *
     * The verifier is 32 random bytes base64url-encoded (no padding) to a 43-character
     * ASCII string — the RFC 7636 §4.1 minimum and the value recommended by the OAuth
     * 2.1 BCP. Going higher (up to RFC 7636's 96-byte maximum) gains no security: the
     * matching SHA-256 challenge is fixed-size regardless of verifier length and the
     * verifier itself is already cryptographically random.
     *
     * @param state OAuth2 `state` parameter chosen by Ktor's NonceManager; the key used
     *   to retrieve the verifier later in [consume].
     * @return The PKCE challenge fields to append to the authorize URL.
     */
    suspend fun begin(state: String): PkceChallenge {
        val verifierBytes = ByteArray(VERIFIER_RANDOM_BYTES).also { secureRandom.nextBytes(it) }
        val verifier = base64UrlNoPad(verifierBytes)
        store.put(state, verifier, ttl)
        val challengeBytes = sha256(verifier.toByteArray(Charsets.US_ASCII))
        return PkceChallenge(challenge = base64UrlNoPad(challengeBytes), method = METHOD_S256)
    }

    /**
     * Look up and atomically consume the verifier previously stored for [state].
     *
     * @return The stored verifier, or `null` if no PKCE flow with [state] exists, the
     *   flow has expired, or the verifier has already been consumed.
     */
    suspend fun consume(state: String): String? = store.consume(state)

    companion object {
        /**
         * Length, in bytes, of the random portion of each generated verifier.
         *
         * 32 bytes encode (no-padding base64url) to 43 ASCII characters — the RFC 7636
         * §4.1 minimum verifier length and the value picked by the OAuth 2.1 BCP.
         */
        const val VERIFIER_RANDOM_BYTES = 32

        /**
         * PKCE challenge method identifier. Always `S256` — RFC 7636 §4.2 deprecates
         * `plain` and modern IdPs require the SHA-256-based variant.
         */
        const val METHOD_S256 = "S256"

        private val secureRandom = SecureRandom()

        /**
         * Base64-url-encode [bytes] without trailing `=` padding, per RFC 7636 §4.2
         * (PKCE challenges must use URL-safe base64 without padding).
         */
        private fun base64UrlNoPad(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /**
         * Compute the SHA-256 digest of [bytes].
         */
        private fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/**
 * Result of [PkceService.begin] — the parameters to append to the OAuth2 authorize URL.
 *
 * @property challenge Value of the `code_challenge` query parameter; base64url-encoded
 *   SHA-256 digest of the stored verifier.
 * @property method Value of the `code_challenge_method` query parameter. Always
 *   [PkceService.METHOD_S256] (`S256`).
 */
data class PkceChallenge(
    val challenge: String,
    val method: String,
)
