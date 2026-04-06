package cz.pizavo.omnisign.domain.repository

/**
 * Result of a certificate discovery operation.
 *
 * [certificates] contains only the certificates that survived the caller's signing-capability
 * filter.  [tokenWarnings] carries one entry per token that could not be enumerated so that
 * callers can surface diagnostic information when the list is unexpectedly empty.
 * [lockedTokens] lists tokens that were skipped because they require a PIN that was not supplied
 * during silent discovery; the UI can offer to unlock them interactively.
 */
data class CertificateDiscoveryResult(
    val certificates: List<AvailableCertificateInfo>,
    val tokenWarnings: List<TokenDiscoveryWarning> = emptyList(),
    val lockedTokens: List<LockedTokenInfo> = emptyList(),
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

/**
 * A token that was discovered but could not be enumerated because it requires a PIN.
 *
 * Presented in the UI as an "unlock" action so the user can supply the PIN on demand
 * and have the token's certificates merged into the signing form.
 *
 * @property tokenId Stable token identifier matching [cz.pizavo.omnisign.domain.service.TokenInfo.id].
 * @property tokenName Human-readable display name of the token.
 * @property tokenTypeName Name of the [cz.pizavo.omnisign.domain.model.config.enums.TokenType].
 */
data class LockedTokenInfo(
    val tokenId: String,
    val tokenName: String,
    val tokenTypeName: String,
)


