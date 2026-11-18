package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig

/**
 * Operation-specific configuration overrides.
 * Used to override settings for a single operation.
 */
data class OperationConfig(
	val hashAlgorithm: HashAlgorithm? = null,
	val encryptionAlgorithm: EncryptionAlgorithm? = null,
	val signatureLevel: SignatureLevel? = null,
	/**
	 * The timestamp server for this operation, merged field by field onto the stored one by
	 * [ResolvedConfig.resolve].
	 */
	val timestampServer: TimestampServerOverride? = null,
	val ocsp: OcspConfig? = null,
	val crl: CrlConfig? = null,
	val validation: ValidationConfig? = null,
	/**
	 * Hash algorithms additionally disabled for this single operation.
	 * Unioned with the active profile's disabled set (which itself unions with global).
	 */
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	/**
	 * Encryption algorithms additionally disabled for this single operation.
	 * Unioned with the active profile's disabled set (which itself unions with global).
	 */
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet()
)


