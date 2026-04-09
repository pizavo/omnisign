package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import kotlinx.serialization.Serializable

/**
 * Sanitized API representation of [GlobalConfig].
 *
 * [GlobalConfig.customPkcs11Libraries] is intentionally omitted because it exposes
 * filesystem paths that are an internal server concern.
 * The timestamp server is replaced with [TimestampServerSummary] to strip credential material.
 *
 * @property defaultHashAlgorithm Default hash algorithm for signing.
 * @property defaultEncryptionAlgorithm Default encryption algorithm override, or `null` for DSS inference.
 * @property defaultSignatureLevel Default PAdES signature level.
 * @property disabledHashAlgorithms Hash algorithms that are globally disabled.
 * @property disabledEncryptionAlgorithms Encryption algorithms that are globally disabled.
 * @property timestampServer Sanitized TSA configuration, or `null` when not configured.
 * @property ocsp OCSP configuration.
 * @property crl CRL configuration.
 * @property validation Validation configuration.
 */
@Serializable
data class GlobalConfigResponse(
	val defaultHashAlgorithm: HashAlgorithm,
	val defaultEncryptionAlgorithm: EncryptionAlgorithm?,
	val defaultSignatureLevel: SignatureLevel,
	val disabledHashAlgorithms: Set<HashAlgorithm>,
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm>,
	val timestampServer: TimestampServerSummary?,
	val ocsp: OcspConfig,
	val crl: CrlConfig,
	val validation: ValidationConfig,
)

/**
 * Map a [GlobalConfig] to a [GlobalConfigResponse], sanitizing sensitive fields.
 */
fun GlobalConfig.toResponse() = GlobalConfigResponse(
	defaultHashAlgorithm = defaultHashAlgorithm,
	defaultEncryptionAlgorithm = defaultEncryptionAlgorithm,
	defaultSignatureLevel = defaultSignatureLevel,
	disabledHashAlgorithms = disabledHashAlgorithms,
	disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
	timestampServer = timestampServer?.toSummary(),
	ocsp = ocsp,
	crl = crl,
	validation = validation,
)

