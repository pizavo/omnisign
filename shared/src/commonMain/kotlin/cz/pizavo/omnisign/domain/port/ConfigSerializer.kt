package cz.pizavo.omnisign.domain.port

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.result.OperationResult

/**
 * Port that abstracts serialization and deserialization of configuration objects
 * to and from a specific text format.
 *
 * Platform-specific implementations live in the corresponding source set (e.g. `jvmMain`).
 */
interface ConfigSerializer {
	/**
	 * The format produced and consumed by this serializer.
	 */
	val format: ConfigFormat
	
	/**
	 * Serialize the full application configuration to a string.
	 *
	 * @param config The configuration to serialize.
	 * @return Serialized text or an error.
	 */
	fun serializeApp(config: AppConfig): OperationResult<String>
	
	/**
	 * Deserialize a full application configuration from a string.
	 *
	 * @param text Previously serialized configuration text.
	 * @return Deserialized [AppConfig] or an error.
	 */
	fun deserializeApp(text: String): OperationResult<AppConfig>
	
	/**
	 * Serialize only the global section of the configuration.
	 *
	 * @param config The global configuration to serialize.
	 * @return Serialized text or an error.
	 */
	fun serializeGlobal(config: GlobalConfig): OperationResult<String>
	
	/**
	 * Deserialize a global configuration from a string.
	 *
	 * @param text Previously serialized global configuration text.
	 * @return Deserialized [GlobalConfig] or an error.
	 */
	fun deserializeGlobal(text: String): OperationResult<GlobalConfig>
	
	/**
	 * Serialize a single profile configuration.
	 *
	 * @param profile The profile to serialize.
	 * @return Serialized text or an error.
	 */
	fun serializeProfile(profile: ProfileConfig): OperationResult<String>
	
	/**
	 * Deserialize a single profile configuration from a string.
	 *
	 * @param text Previously serialized profile configuration text.
	 * @return Deserialized [ProfileConfig] or an error.
	 */
	fun deserializeProfile(text: String): OperationResult<ProfileConfig>
}

