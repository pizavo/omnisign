package cz.pizavo.omnisign.domain.repository

/**
 * Result of a certificate discovery operation.
 *
 * [certificates] contains only the certificates that survived the caller's signing-capability
 * filter.  [tokenWarnings] carries one entry per token that could not be enumerated so that
 * callers can surface diagnostic information when the list is unexpectedly empty.
 */
data class CertificateDiscoveryResult(
    val certificates: List<AvailableCertificateInfo>,
    val tokenWarnings: List<TokenDiscoveryWarning> = emptyList(),
)

/**
 * Describes a per-token failure encountered during certificate discovery.
 *
 * @property tokenId Stable token identifier matching [cz.pizavo.omnisign.domain.service.TokenInfo.id].
 * @property tokenName Human-readable display name of the token.
 * @property message Short description of why the token could not be accessed.
 * @property details Optional underlying exception message for deeper diagnostics.
 */
data class TokenDiscoveryWarning(
    val tokenId: String,
    val tokenName: String,
    val message: String,
    val details: String? = null,
)


