package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import kotlinx.serialization.Serializable

/**
 * Profile-specific configuration.
 * Each field can override the corresponding global setting.
 */
@Serializable
data class ProfileConfig(
	/**
	 * Profile name.
	 */
	val name: String,
	
	/**
	 * Profile description.
	 */
	val description: String? = null,
	
	/**
	 * Override default hash algorithm.
	 */
	val hashAlgorithm: HashAlgorithm? = null,
	
	/**
	 * Override the default encryption (signing key) algorithm.
	 * Null means fall back to the global setting (or DSS key-type inference).
	 */
	val encryptionAlgorithm: EncryptionAlgorithm? = null,
	
	/**
	 * Hash algorithms disabled by this profile in addition to any that are already disabled
	 * at the global level.  Algorithms in this set cannot be selected by an operation override
	 * when this profile is active.  Empty means no extra restriction beyond the global set.
	 */
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	
	/**
	 * Encryption algorithms disabled by this profile in addition to any that are already
	 * disabled at the global level.  Empty means no extra restriction beyond the global set.
	 */
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	
	/**
	 * Override default signature level.
	 */
	val signatureLevel: SignatureLevel? = null,
	
	/**
	 * Override timestamp server configuration.
	 */
	val timestampServer: TimestampServerConfig? = null,
	
	/**
	 * Override OCSP configuration.
	 */
	val ocsp: OcspConfig? = null,
	
	/**
	 * Override CRL configuration.
	 */
	val crl: CrlConfig? = null,
	
	/**
	 * Override validation configuration.
	 */
	val validation: ValidationConfig? = null
)

/**
 * Why this profile cannot be saved, or `null` when it can.
 *
 * A [ProfileConfig.hashAlgorithm] or [ProfileConfig.encryptionAlgorithm] override that is also
 * among the algorithms the profile disables would make every resolution through the profile fail,
 * so the use cases that save a profile, an upsert and an import alike, refuse one with this error
 * before anything is saved.
 */
fun ProfileConfig.disabledOverrideError(): ConfigurationError.InvalidConfiguration? {
	val hash = hashAlgorithm
	if (hash != null && hash in disabledHashAlgorithms) {
		return ConfigurationError.profileDisablesOwnHash(name, hash.name)
	}
	val encryption = encryptionAlgorithm
	if (encryption != null && encryption in disabledEncryptionAlgorithms) {
		return ConfigurationError.profileDisablesOwnEncryption(name, encryption.name)
	}
	return null
}
