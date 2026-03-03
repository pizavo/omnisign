package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import kotlinx.serialization.Serializable

/**
 * Global configuration settings.
 */
@Serializable
data class GlobalConfig(
	/**
	 * Default hash algorithm for signing.
	 */
	val defaultHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
	
	/**
	 * Default encryption (signing key) algorithm for signing.
	 * Null means DSS will infer the algorithm from the certificate's key type.
	 */
	val defaultEncryptionAlgorithm: EncryptionAlgorithm? = null,
	
	/**
	 * Default signature level.
	 */
	val defaultSignatureLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_B,
	
	/**
	 * Hash algorithms that are globally disabled and cannot be selected at any level.
	 * A profile or operation override that names a globally disabled algorithm is rejected
	 * during config resolution.
	 */
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	
	/**
	 * Encryption algorithms that are globally disabled and cannot be selected at any level.
	 * A profile or operation override that names a globally disabled algorithm is rejected
	 * during config resolution.
	 */
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	
	/**
	 * Timestamp server configuration.
	 */
	val timestampServer: TimestampServerConfig? = null,
	
	/**
	 * OCSP configuration.
	 */
	val ocsp: OcspConfig = OcspConfig(),
	
	/**
	 * CRL configuration.
	 */
	val crl: CrlConfig = CrlConfig(),
	
	/**
	 * Validation policy settings.
	 */
	val validation: ValidationConfig = ValidationConfig()
)


