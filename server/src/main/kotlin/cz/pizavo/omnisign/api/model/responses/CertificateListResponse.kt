package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import kotlinx.serialization.Serializable

/**
 * API response for the `GET /api/v1/certificates` endpoint.
 *
 * @property certificates Signing-capable certificates available on this server,
 *   filtered by [cz.pizavo.omnisign.config.ServerConfig.allowedCertificateAliases] when set.
 * @property tokenWarnings Per-token warnings collected during discovery. Each entry
 *   describes a token that could not be enumerated and the reason why.
 * @property lockedTokens Tokens that were discovered but skipped because they require
 *   a PIN that the server cannot supply interactively.
 */
@Serializable
data class CertificateListResponse(
	val certificates: List<CertificateInfoResponse>,
	val tokenWarnings: List<TokenWarning>,
	val lockedTokens: List<LockedToken>,
) {
	/**
	 * Describes a per-token failure encountered during certificate discovery.
	 *
	 * @property tokenId Stable token identifier.
	 * @property tokenName Human-readable display name of the token.
	 * @property message Short description of why the token could not be accessed.
	 * @property details Optional underlying exception message for deeper diagnostics.
	 */
	@Serializable
	data class TokenWarning(
		val tokenId: String,
		val tokenName: String,
		val message: String,
		val details: String?,
	)

	/**
	 * A token that was discovered but could not be enumerated because it requires a PIN.
	 *
	 * @property tokenId Stable token identifier.
	 * @property tokenName Human-readable display name of the token.
	 * @property tokenTypeName Name of the token type.
	 */
	@Serializable
	data class LockedToken(
		val tokenId: String,
		val tokenName: String,
		val tokenTypeName: String,
	)
}

/**
 * Map a [CertificateDiscoveryResult] to a [CertificateListResponse].
 */
fun CertificateDiscoveryResult.toResponse() = CertificateListResponse(
	certificates = certificates.map { it.toResponse() },
	tokenWarnings = tokenWarnings.map {
		CertificateListResponse.TokenWarning(
			tokenId = it.tokenId,
			tokenName = it.tokenName,
			message = it.message,
			details = it.details,
		)
	},
	lockedTokens = lockedTokens.map {
		CertificateListResponse.LockedToken(
			tokenId = it.tokenId,
			tokenName = it.tokenName,
			tokenTypeName = it.tokenTypeName,
		)
	},
)

