package cz.pizavo.omnisign.config

/**
 * Supported JWT signing algorithm variants.
 *
 * ## Choosing an algorithm
 *
 * ### HMAC (symmetric — shared secret)
 * All three variants use the same `auth.session.secret` / `OMNISIGN_JWT_SECRET` value.
 * The secret must be kept confidential on the server; any party that can verify a token
 * with HMAC can also forge one.
 *
 * | Type  | Hash    | Notes |
 * |-------|---------|-------|
 * | HS256 | SHA-256 | Widely supported; no reason to prefer over [HS512]. |
 * | HS384 | SHA-384 | Rarely needed; prefer [HS512]. |
 * | HS512 | SHA-512 | **Recommended default.** SHA-512 is typically faster than SHA-256 on 64-bit hardware. |
 *
 * ### RSA / ECDSA (asymmetric — key pair) — planned, not yet implemented
 * Asymmetric algorithms are relevant when a second service must verify OmniSign tokens
 * without sharing the signing secret. For example, an external document archive that
 * accepts OmniSign tokens as proof of identity or a multi-instance deployment where
 * another must accept tokens from one server.
 *
 * For a single-server deployment where the same process both issues and validates tokens,
 * HMAC ([HS512]) is equally secure and far simpler to operate. TLS protects the token in
 * transit; the JWT algorithm choice does not guard against MITM.
 *
 * | Type  | Notes |
 * |-------|-------|
 * | RS256 | RSA PKCS#1 v1.5 / SHA-256. Broad library support. |
 * | RS384 | RSA PKCS#1 v1.5 / SHA-384. |
 * | RS512 | RSA PKCS#1 v1.5 / SHA-512. |
 * | ES256 | ECDSA P-256 / SHA-256. **Recommended asymmetric default.** NIST-approved, universally supported (RFC 7518). |
 * | ES384 | ECDSA P-384 / SHA-384. |
 * | ES512 | ECDSA P-521 / SHA-512. P-521 offers no practical advantage over P-256 for short-lived tokens and is less widely tested. Prefer [ES256]. |
 */
enum class JwtAlgorithmType {
	
	/** HMAC-SHA256. Prefer [HS512]. */
	HS256,
	
	/** HMAC-SHA384. */
	HS384,
	
	/** HMAC-SHA512. Recommended for symmetric (single-server) deployments. */
	HS512,
	
	/** RSA PKCS#1 v1.5 / SHA-256. Requires an RSA key pair. Planned — not yet implemented. */
	RS256,
	
	/** RSA PKCS#1 v1.5 / SHA-384. Requires an RSA key pair. Planned — not yet implemented. */
	RS384,
	
	/** RSA PKCS#1 v1.5 / SHA-512. Requires an RSA key pair. Planned — not yet implemented. */
	RS512,
	
	/**
	 * ECDSA P-256 / SHA-256.
	 *
	 * Recommended choice when asymmetric signing is required (e.g., third-party token
	 * verification without sharing the secret). Requires an EC key pair.
	 * Planned — not yet implemented.
	 */
	ES256,
	
	/** ECDSA P-384 / SHA-384. Requires an EC key pair. Planned — not yet implemented. */
	ES384,
	
	/**
	 * ECDSA P-521 / SHA-512.
	 *
	 * Despite the larger curve, P-521 provides no measurable security improvement over
	 * [ES256] for short-lived JWT tokens and has historically seen fewer independent
	 * implementations. Prefer [ES256] unless a compliance requirement mandates P-521.
	 * Requires an EC key pair. Planned — not yet implemented.
	 */
	ES512,
}

/**
 * `true` for symmetric HMAC variants ([HS256][JwtAlgorithmType.HS256], [HS384][JwtAlgorithmType.HS384], [HS512][JwtAlgorithmType.HS512])
 * which use a shared secret; `false` for asymmetric key-pair variants.
 */
val JwtAlgorithmType.isSymmetric: Boolean
	get() = this == JwtAlgorithmType.HS256
			|| this == JwtAlgorithmType.HS384
			|| this == JwtAlgorithmType.HS512

