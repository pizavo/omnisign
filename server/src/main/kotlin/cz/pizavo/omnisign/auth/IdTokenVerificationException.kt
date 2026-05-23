package cz.pizavo.omnisign.auth

/**
 * Sealed hierarchy of failure modes returned by [IdTokenVerifier.verify].
 *
 * Each subtype identifies a single distinct verification failure so the calling route
 * handler can map it to a specific error code in the response body. All subtypes carry
 * a human-readable message; some carry a structured field (e.g., [KeyNotFound.kid],
 * [ClaimInvalid.claim]) for log/error correlation.
 *
 * @param message Human-readable diagnostic message.
 * @param cause Underlying exception from the JWT or JWKS library, when applicable.
 */
sealed class IdTokenVerificationException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * The id_token string is not parseable as a JWT (e.g., not three base64url segments
     * separated by `.`).
     */
    class Malformed(cause: Throwable) :
        IdTokenVerificationException("id_token is not a valid JWT", cause)

    /**
     * The id_token's JWS header does not include a `kid` (key ID), so we cannot select
     * the correct public key from the IdP's JWKS. Modern IdPs always set `kid`; missing
     * `kid` is either a misconfiguration or an attempt to bypass key selection.
     */
    class MissingKid :
        IdTokenVerificationException("id_token JWS header has no `kid`; cannot select JWKS key")

    /**
     * No public key with the requested `kid` was found in the IdP's JWKS.
     *
     * @property kid The `kid` value from the id_token header that could not be resolved.
     */
    class KeyNotFound(val kid: String, cause: Throwable) :
        IdTokenVerificationException("No JWKS key with kid '$kid'", cause)

    /**
     * The id_token's `alg` claim names a signature algorithm this verifier does not
     * support. Only RSA (RS256/RS384/RS512) and ECDSA (ES256/ES384/ES512) are accepted;
     * `none`, HMAC variants, and other algorithms are rejected.
     *
     * @property algorithm The `alg` value from the id_token JWS header.
     */
    class UnsupportedAlgorithm(val algorithm: String) :
        IdTokenVerificationException(
            "id_token uses unsupported algorithm '$algorithm' (only RS256/RS384/RS512 and " +
                "ES256/ES384/ES512 are accepted)",
        )

    /**
     * The id_token signature does not validate against the JWKS public key, or one of
     * the standard claim checks (`iss`, `aud`, `exp`, `nbf`) failed.
     */
    class VerificationFailed(message: String, cause: Throwable) :
        IdTokenVerificationException(message, cause)

    /**
     * The id_token is structurally valid and signed correctly but a required claim is
     * missing or has an unexpected value.
     *
     * @property claim Name of the offending claim.
     */
    class ClaimInvalid(val claim: String, reason: String) :
        IdTokenVerificationException("id_token claim '$claim' invalid: $reason")
}
