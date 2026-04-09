package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import kotlinx.serialization.Serializable

/**
 * Sanitized API representation of [ResolvedConfig] for a given profile selection.
 *
 * The timestamp server is replaced with [TimestampServerSummary] to strip credential material.
 * [resolvedProfileName] is `null` when no profile was supplied and global defaults apply.
 *
 * @property resolvedProfileName Name of the profile that was applied, or `null` for global defaults.
 * @property hashAlgorithm Effective hash algorithm.
 * @property encryptionAlgorithm Effective encryption algorithm, or `null` for DSS inference.
 * @property signatureLevel Effective PAdES signature level.
 * @property timestampServer Sanitized effective TSA configuration, or `null`.
 * @property ocsp Effective OCSP configuration.
 * @property crl Effective CRL configuration.
 * @property validation Effective validation configuration.
 * @property disabledHashAlgorithms Union of all disabled hash algorithms across resolved layers.
 * @property disabledEncryptionAlgorithms Union of all disabled encryption algorithms across resolved layers.
 */
@Serializable
data class ResolvedConfigResponse(
	val resolvedProfileName: String?,
	val hashAlgorithm: HashAlgorithm,
	val encryptionAlgorithm: EncryptionAlgorithm?,
	val signatureLevel: SignatureLevel,
	val timestampServer: TimestampServerSummary?,
	val ocsp: OcspConfig,
	val crl: CrlConfig,
	val validation: ValidationConfig,
	val disabledHashAlgorithms: Set<HashAlgorithm>,
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm>,
)

/**
 * Map a [ResolvedConfig] to a [ResolvedConfigResponse], sanitizing sensitive fields.
 *
 * @param resolvedProfileName The profile name that was passed to resolution, or `null`.
 */
fun ResolvedConfig.toResponse(resolvedProfileName: String?) = ResolvedConfigResponse(
	resolvedProfileName = resolvedProfileName,
	hashAlgorithm = hashAlgorithm,
	encryptionAlgorithm = encryptionAlgorithm,
	signatureLevel = signatureLevel,
	timestampServer = timestampServer?.toSummary(),
	ocsp = ocsp,
	crl = crl,
	validation = validation,
	disabledHashAlgorithms = disabledHashAlgorithms,
	disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
)

