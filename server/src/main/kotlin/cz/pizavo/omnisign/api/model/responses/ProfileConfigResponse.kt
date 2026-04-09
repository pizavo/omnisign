package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import kotlinx.serialization.Serializable

/**
 * Sanitized API representation of [ProfileConfig].
 *
 * The timestamp server is replaced with [TimestampServerSummary] to strip credential material.
 * All other fields mirror [ProfileConfig] exactly.
 *
 * @property name Profile name.
 * @property description Human-readable description.
 * @property hashAlgorithm Hash algorithm override, or `null` to inherit from global.
 * @property encryptionAlgorithm Encryption algorithm override, or `null` to inherit.
 * @property disabledHashAlgorithms Additional hash algorithms disabled by this profile.
 * @property disabledEncryptionAlgorithms Additional encryption algorithms disabled by this profile.
 * @property signatureLevel PAdES level override, or `null` to inherit.
 * @property timestampServer Sanitized TSA override, or `null` to inherit.
 * @property ocsp OCSP override, or `null` to inherit.
 * @property crl CRL override, or `null` to inherit.
 * @property validation Validation config override, or `null` to inherit.
 */
@Serializable
data class ProfileConfigResponse(
	val name: String,
	val description: String?,
	val hashAlgorithm: HashAlgorithm?,
	val encryptionAlgorithm: EncryptionAlgorithm?,
	val disabledHashAlgorithms: Set<HashAlgorithm>,
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm>,
	val signatureLevel: SignatureLevel?,
	val timestampServer: TimestampServerSummary?,
	val ocsp: OcspConfig?,
	val crl: CrlConfig?,
	val validation: ValidationConfig?,
)

/**
 * Map a [ProfileConfig] to a [ProfileConfigResponse], sanitizing sensitive fields.
 */
fun ProfileConfig.toResponse() = ProfileConfigResponse(
	name = name,
	description = description,
	hashAlgorithm = hashAlgorithm,
	encryptionAlgorithm = encryptionAlgorithm,
	disabledHashAlgorithms = disabledHashAlgorithms,
	disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
	signatureLevel = signatureLevel,
	timestampServer = timestampServer?.toSummary(),
	ocsp = ocsp,
	crl = crl,
	validation = validation,
)

