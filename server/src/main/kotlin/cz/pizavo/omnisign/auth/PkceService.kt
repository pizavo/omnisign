package cz.pizavo.omnisign.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration

/**
 * PKCE (RFC 7636) protocol helper.
 *
 * On the authorization-code redirect step, [begin] generates a per-flow code verifier,
 * persists it via [PkceVerifierStore] keyed by the OAuth2 `state` parameter, and returns
 * the matching S256 challenge for inclusion in the authorize URL as
 * `code_challenge` + `code_challenge_method=S256`. On the callback step, [consume]
 * retrieves the verifier so the token-exchange POST can include it as `code_verifier`.
 *
 * Why PKCE for a confidential client like OmniSign: the `client_secret` keeps anyone else
 * from redeeming a stolen authorization code, but not from injecting it. An attacker who
 * steals a victim's code (server log, proxy log, `Referer` header, browser history) can hand
 * it to this server's callback in a login of their own, and the server redeems it with its
 * secret. PKCE binds the code to the login that requested it: the token endpoint wants the
 * verifier behind that login's challenge, which travels only from this server to the IdP's
 * token endpoint. The binding also holds if the `client_secret` leaks (env-var dump, leaked
 * deploy config).
 *
 * RFC 9700, the OAuth 2.0 Security Best Current Practice, requires PKCE of public clients and
 * recommends it for confidential ones such as this server (§2.1.1). It lets a confidential
 * OpenID Connect client use the `nonce` parameter instead, which OmniSign does not send. The
 * OAuth 2.1 draft requires PKCE of every client except a confidential one that uses `nonce`,
 * and recommends it there too.
 *
 * The same construction is reused a second time, one hop further on. [challengeFor] and
 * [verifyChallenge] are the verifier side of the mechanism without the store, and
 * [HandoffCodeStore] uses them to bind its hand-off code to the single-page app that
 * started the login — the code travels in a URL and faces exactly the leak paths listed
 * above, so it gets exactly the same defence. This class stays the one place that knows
 * how an S256 challenge is derived.
 *
 * @param store Backing store of `state → verifier` mappings. Concrete implementation
 *   typically [ExposedPkceVerifierStore].
 * @param ttl Per-flow lifetime of a stored verifier. Defaults to [LOGIN_FLOW_TTL], the
 *   lifetime every record of a login shares.
 */
class PkceService(
    private val store: PkceVerifierStore,
    private val ttl: Duration = LOGIN_FLOW_TTL,
) {

    /**
     * Generate a fresh PKCE code verifier for [state], persist it via the backing store,
     * and return the matching S256 challenge for the authorize URL.
     *
     * The verifier is 32 random bytes base64url-encoded (no padding) to a 43-character
     * ASCII string — the RFC 7636 §4.1 minimum, and the length that section recommends.
     * Going higher (up to RFC 7636's 96-byte maximum) gains no security: the
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
        return PkceChallenge(challenge = challengeFor(verifier), method = METHOD_S256)
    }

    /**
     * Look up and atomically consume the verifier previously stored for [state].
     *
     * @return The stored verifier, or `null` if no PKCE flow with [state] exists, the
     *   flow has expired, or the verifier has already been consumed.
     */
    suspend fun consume(state: String): String? = store.consume(state)

    /**
     * Derive the `S256` challenge for [verifier] — RFC 7636 §4.2's
     * `BASE64URL(SHA256(ASCII(verifier)))`.
     *
     * @param verifier A PKCE code verifier.
     * @return The base64url-encoded, unpadded SHA-256 digest.
     */
    fun challengeFor(verifier: String): String =
        base64UrlNoPad(sha256(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * Whether [verifier] is the pre-image of [challenge] under `S256` — RFC 7636 §4.6's
     * comparison, expressed the way round.
     *
     * The comparison is constant-time even though neither side is secret (the challenge was
     * published in an authorize URL, and SHA-256 keeps the verifier out of reach of anyone
     * holding it). It costs nothing, and the alternative invites a reader to work out
     * whether the exception is sound rather than reading on.
     *
     * @param verifier The verifier presented by the client.
     * @param challenge The challenge recorded when the flow began.
     * @return `true` when [verifier] hashes to [challenge].
     */
    fun verifyChallenge(verifier: String, challenge: String): Boolean =
        MessageDigest.isEqual(
            challengeFor(verifier).toByteArray(Charsets.US_ASCII),
            challenge.toByteArray(Charsets.US_ASCII),
        )

    companion object {
        /**
         * Length, in bytes, of the random portion of each generated verifier.
         *
         * 32 bytes encode (no-padding base64url) to 43 ASCII characters — the RFC 7636
         * §4.1 minimum verifier length, and the 32 octets that section recommends.
         */
        const val VERIFIER_RANDOM_BYTES = 32

        /**
         * PKCE challenge method identifier. Always `S256`: RFC 7636 §4.2 requires it of every
         * client able to compute it, and RFC 9700 §2.1.1 asks for a method that does not expose
         * the verifier in the authorization request, which only `S256` is.
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
