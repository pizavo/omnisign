package cz.pizavo.omnisign.domain.model.config.enums

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Hash algorithm options for digital signatures.
 */
@Serializable
enum class HashAlgorithm {
	SHA256,
	SHA384,
	SHA512,
	SHA3_256,
	SHA3_384,
	SHA3_512,
	WHIRLPOOL,
	RIPEMD160;
	
	/**
	 * The DSS algorithm name used when constructing DSS signing parameters.
	 */
	val dssName: String
		get() = when (this) {
			SHA256 -> "SHA256"
			SHA384 -> "SHA384"
			SHA512 -> "SHA512"
			SHA3_256 -> "SHA3-256"
			SHA3_384 -> "SHA3-384"
			SHA3_512 -> "SHA3-512"
			WHIRLPOOL -> "WHIRLPOOL"
			RIPEMD160 -> "RIPEMD160"
		}
	
	/**
	 * Human-readable description of the algorithm family and output size.
	 */
	val description: String
		get() = when (this) {
			SHA256 -> "SHA-2 family, 256-bit output — widely recommended baseline"
			SHA384 -> "SHA-2 family, 384-bit output — stronger SHA-2 variant"
			SHA512 -> "SHA-2 family, 512-bit output — strongest SHA-2 variant"
			SHA3_256 -> "SHA-3 (Keccak) family, 256-bit output — NIST standardised 2015"
			SHA3_384 -> "SHA-3 (Keccak) family, 384-bit output"
			SHA3_512 -> "SHA-3 (Keccak) family, 512-bit output"
			WHIRLPOOL -> "Whirlpool (ISO/IEC 10118-3), 512-bit output — Miyaguchi-Preneel construction"
			RIPEMD160 -> "RIPEMD-160, 160-bit output — RIPE consortium / ISO/IEC 10118-3"
		}
	
	/**
	 * Additional caveats or support notes shown by `algorithm hash list`, or null when none apply.
	 */
	val notes: String?
		get() = when (this) {
			RIPEMD160 -> "160-bit output offers lower collision resistance than SHA-256; use only when interoperability requires it; not supported by Windows Certificate Store"
			WHIRLPOOL -> "Not in ETSI/eIDAS recommended list; supported by DSS but uncommon in practice; not supported by Windows Certificate Store"
			SHA3_256, SHA3_384, SHA3_512 -> "Not supported by Windows Certificate Store — Windows CNG does not implement SHA-3; use a PKCS#11 or file-based token for SHA-3 signing"
			else -> null
		}

	/**
	 * Whether this algorithm is supported by the Windows CNG cryptography provider
	 * (exposed to the JVM via `SunMSCAPI`).
	 *
	 * Windows CNG only supports the SHA-2 family (SHA-256, SHA-384, SHA-512) for ECDSA and RSA
	 * signing. SHA-3 family, Whirlpool, and RIPEMD-160 are not implemented by Windows CNG and
	 * will cause a JCA `InvalidKeyException` at runtime when using a `WINDOWS_MY` token.
	 * Confirmed failing with DSS 6.3 + Java 21 on Windows 11 with an ECDSA certificate.
	 */
	val isMscapiCompatible: Boolean
		get() = this in setOf(SHA256, SHA384, SHA512)

	/**
	 * The date after which this algorithm is considered cryptographically expired per
	 * ETSI TS 119 312 as encoded in the DSS default ETSI validation policy.
	 *
	 * A `null` value means the algorithm has no defined expiration date and is
	 * considered valid indefinitely under current ETSI guidance.
	 *
	 * Source: `AlgoExpirationDate` block in `dss-policy-jaxb` `constraint.xml` (DSS 6.3).
	 */
	val expirationDate: LocalDate?
		get() = when (this) {
			RIPEMD160 -> LocalDate(2014, 8, 1)
			WHIRLPOOL -> LocalDate(2020, 12, 1)
			else -> null
		}
}
