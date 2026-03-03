package cz.pizavo.omnisign.domain.model.config.enums

import kotlinx.serialization.Serializable

/**
 * Asymmetric encryption (signing key) algorithms supported for PAdES digital signatures.
 *
 * The actual signature algorithm used by the DSS library is a combination of this
 * value and the selected [HashAlgorithm].  Not every combination is valid; see
 * [compatibleHashAlgorithms] and [isCompatibleWith] for the compatibility matrix.
 */
@Serializable
enum class EncryptionAlgorithm {
	
	/** RSA PKCS#1 v1.5 padding.  Widely supported, most common on software tokens. */
	RSA,
	
	/**
	 * RSA-PSS (RSASSA-PSS) probabilistic padding.
	 * Stronger than plain RSA PKCS#1; required by ETSI for new advanced signatures.
	 */
	RSA_SSA_PSS,
	
	/**
	 * ECDSA — Elliptic-Curve Digital Signature Algorithm (DER-encoded, SEC 1 / ANSI X9.62 format).
	 * Typical format on smartcards and PKCS#11 tokens.
	 */
	ECDSA,
	
	/**
	 * Plain-ECDSA — ECDSA with plain (r||s) integer encoding.
	 * Common on CVC/ePassport cards; different on-wire format from [ECDSA].
	 */
	PLAIN_ECDSA,
	
	/**
	 * DSA — Digital Signature Algorithm (original FIPS 186 DSA with DSA keys).
	 * Rarely used on modern systems; provided for legacy interoperability.
	 */
	DSA,
	
	/**
	 * EdDSA — Edwards-curve Digital Signature Algorithm (covers both Ed25519 and Ed448).
	 *
	 * The hash algorithm is intrinsic to the algorithm and **cannot be overridden**:
	 * Ed25519 uses SHA-512 internally; Ed448 uses SHAKE256.  The [HashAlgorithm]
	 * selection is therefore ignored when this encryption algorithm is active.
	 */
	EDDSA;
	
	/**
	 * The DSS [eu.europa.esig.dss.enumerations.EncryptionAlgorithm] name used when
	 * constructing DSS signing parameters.
	 */
	val dssName: String
		get() = when (this) {
			RSA -> "RSA"
			RSA_SSA_PSS -> "RSASSA-PSS"
			ECDSA -> "ECDSA"
			PLAIN_ECDSA -> "PLAIN-ECDSA"
			DSA -> "DSA"
			EDDSA -> "EDDSA"
		}
	
	/**
	 * Human-readable description of the algorithm.
	 */
	val description: String
		get() = when (this) {
			RSA -> "RSA PKCS#1 v1.5 — widely supported, standard on software keystores and PKCS#12 files"
			RSA_SSA_PSS -> "RSA-PSS (RSASSA-PSS) — probabilistic padding, stronger than PKCS#1 v1.5; eIDAS-recommended for new RSA signatures"
			ECDSA -> "ECDSA (DER-encoded) — elliptic-curve DSA; compact keys with equivalent security to RSA at larger sizes"
			PLAIN_ECDSA -> "ECDSA plain (r‖s) — same cryptography as ECDSA with different signature encoding; common on smartcard/CVC tokens"
			DSA -> "DSA (FIPS 186) — legacy algorithm; provided for interoperability, RSA or ECDSA preferred for new keys"
			EDDSA -> "EdDSA (Ed25519/Ed448) — modern deterministic Edwards-curve algorithm; hash algorithm is intrinsic and cannot be overridden"
		}
	
	/**
	 * Additional usage notes or caveats, or `null` when none apply.
	 */
	val notes: String?
		get() = when (this) {
			EDDSA -> "Hash algorithm selection is ignored for EdDSA — Ed25519 uses SHA-512, Ed448 uses SHAKE256"
			DSA -> "DSA keys are rare on modern tokens; prefer ECDSA or RSA for new signatures"
			PLAIN_ECDSA -> "Use only when your token produces r‖s-encoded ECDSA signatures; most software tokens use standard DER ECDSA"
			else -> null
		}
	
	/**
	 * Whether this algorithm has a fixed (intrinsic) hash algorithm that cannot be
	 * selected by the user.
	 */
	val hasFixedHashAlgorithm: Boolean
		get() = this == EDDSA
	
	/**
	 * The set of [HashAlgorithm] values that are valid for use with this encryption algorithm.
	 *
	 * Returns an empty set for [EDDSA] because the hash algorithm is intrinsic and
	 * cannot be configured externally.
	 *
	 * The matrix is derived from [eu.europa.esig.dss.enumerations.SignatureAlgorithm]:
	 * - RSA/RSA_SSA_PSS: SHA-2, SHA-3 families, RIPEMD-160 (but not Whirlpool)
	 * - ECDSA/PLAIN_ECDSA: SHA-2, SHA-3 families, RIPEMD-160 (but not Whirlpool)
	 * - DSA: SHA-2 and SHA-3 families only (no RIPEMD-160, no Whirlpool)
	 * - EdDSA: intrinsic — empty set (no external selection possible)
	 */
	val compatibleHashAlgorithms: Set<HashAlgorithm>
		get() = when (this) {
			RSA, RSA_SSA_PSS, ECDSA, PLAIN_ECDSA -> setOf(
				HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
				HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512,
				HashAlgorithm.RIPEMD160
			)
			
			DSA -> setOf(
				HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
				HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512
			)
			
			EDDSA -> emptySet()
		}
	
	/**
	 * Returns `true` when [hash] is compatible with this encryption algorithm for
	 * PAdES signing.  Always returns `true` for [EDDSA] because the hash is fixed
	 * by the algorithm itself and the user selection is simply ignored.
	 */
	fun isCompatibleWith(hash: HashAlgorithm): Boolean =
		hasFixedHashAlgorithm || hash in compatibleHashAlgorithms
}

