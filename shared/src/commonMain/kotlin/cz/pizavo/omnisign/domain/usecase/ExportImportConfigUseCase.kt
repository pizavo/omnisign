package cz.pizavo.omnisign.domain.usecase

import arrow.core.getOrElse
import arrow.core.left
import cz.pizavo.omnisign.domain.model.config.disabledDefaultError
import cz.pizavo.omnisign.domain.model.config.disabledOverrideError
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.port.ConfigSerializer
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.repository.ConfigRepository

/**
 * Use case for exporting and importing application and profile configurations
 * in a variety of text formats (JSON, XML, YAML).
 *
 * Export operations read from the active configuration and produce a serialized string.
 * Import operations deserialize a string and merge the result into the stored configuration.
 */
class ExportImportConfigUseCase(
	private val configRepository: ConfigRepository,
	private val serializerRegistry: ConfigSerializerRegistry
) {
	/**
	 * Export the entire application configuration in the given format.
	 *
	 * @param format Target serialization format.
	 * @return Serialized configuration text or an error.
	 */
	suspend fun exportApp(format: ConfigFormat): OperationResult<String> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.noSerializerForFormat(format.name).left()
		val config = configRepository.getCurrentConfig()
		return serializer.serializeApp(config)
	}
	
	/**
	 * Import and replace the entire application configuration from serialized text.
	 *
	 * Text that holds the global settings or a profile instead, or no configuration, is refused and
	 * nothing is saved, as [ConfigSerializer.deserializeApp] describes. So is a configuration whose
	 * global defaults or a profile's overrides are among the algorithms the same layer disables, as
	 * [disabledDefaultError] and [disabledOverrideError] describe.
	 *
	 * @param text Serialized configuration text.
	 * @param format Format of the supplied text.
	 * @return Unit on success or an error.
	 */
	suspend fun importApp(text: String, format: ConfigFormat): OperationResult<Unit> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.noSerializerForFormat(format.name).left()
		val config = serializer.deserializeApp(text).getOrElse { return it.left() }
		config.global.disabledDefaultError()?.let { return it.left() }
		config.profiles.values.firstNotNullOfOrNull { it.disabledOverrideError() }?.let { return it.left() }
		return configRepository.saveConfig(config)
	}
	
	/**
	 * Export only the global configuration section in the given format.
	 *
	 * @param format Target serialization format.
	 * @return Serialized global configuration text or an error.
	 */
	suspend fun exportGlobal(format: ConfigFormat): OperationResult<String> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.noSerializerForFormat(format.name).left()
		val config = configRepository.getCurrentConfig()
		return serializer.serializeGlobal(config.global)
	}
	
	/**
	 * Import and replace the global configuration section from serialized text.
	 * All other configuration sections (profiles, renewal jobs, etc.) are preserved.
	 *
	 * Text that holds a full configuration or a profile instead, or no configuration, is refused and
	 * nothing is saved, as [ConfigSerializer.deserializeGlobal] describes. So is a global section
	 * whose default hash or encryption algorithm is among the algorithms it disables, as
	 * [disabledDefaultError] describes.
	 *
	 * @param text Serialized global configuration text.
	 * @param format Format of the supplied text.
	 * @return Unit on success or an error.
	 */
	suspend fun importGlobal(text: String, format: ConfigFormat): OperationResult<Unit> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.noSerializerForFormat(format.name).left()
		val global = serializer.deserializeGlobal(text).getOrElse { return it.left() }
		global.disabledDefaultError()?.let { return it.left() }
		val current = configRepository.getCurrentConfig()
		return configRepository.saveConfig(current.copy(global = global))
	}
	
	/**
	 * Export a single named profile in the given format.
	 *
	 * @param profileName Name of the profile to export.
	 * @param format Target serialization format.
	 * @return Serialized profile text or an error.
	 */
	suspend fun exportProfile(profileName: String, format: ConfigFormat): OperationResult<String> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.noSerializerForFormat(format.name).left()
		val config = configRepository.getCurrentConfig()
		val profile = config.profiles[profileName]
			?: return ConfigurationError.profileNotFound(profileName).left()
		return serializer.serializeProfile(profile)
	}
	
	/**
	 * Import a profile from serialized text and upsert it into the stored configuration.
	 * If [overrideName] is provided, it replaces the name embedded in the serialized profile.
	 *
	 * Text that holds a full configuration or the global settings instead, or no configuration, is
	 * refused and nothing is saved, as [ConfigSerializer.deserializeProfile] describes. So is a
	 * profile whose hash or encryption override is among the algorithms it disables, as
	 * [disabledOverrideError] describes; the error names the profile as it would have been saved.
	 *
	 * @param text Serialized profile text.
	 * @param format Format of the supplied text.
	 * @param overrideName Optional name to use instead of the one in the serialized text.
	 * @return The name under which the profile was saved, or an error.
	 */
	suspend fun importProfile(
		text: String,
		format: ConfigFormat,
		overrideName: String? = null
	): OperationResult<String> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.noSerializerForFormat(format.name).left()
		val profile = serializer.deserializeProfile(text).getOrElse { return it.left() }
		val finalProfile = if (overrideName != null) profile.copy(name = overrideName) else profile
		finalProfile.disabledOverrideError()?.let { return it.left() }
		val current = configRepository.getCurrentConfig()
		val updated = current.copy(
			profiles = current.profiles + (finalProfile.name to finalProfile)
		)
		return configRepository.saveConfig(updated).map { finalProfile.name }
	}
}

