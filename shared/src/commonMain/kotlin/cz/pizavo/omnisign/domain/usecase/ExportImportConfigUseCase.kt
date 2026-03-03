package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
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
			?: return ConfigurationError.InvalidConfiguration(
				message = "No serializer registered for format $format"
			).left()
		val config = configRepository.getCurrentConfig()
		return serializer.serializeApp(config)
	}
	
	/**
	 * Import and replace the entire application configuration from serialized text.
	 *
	 * @param text Serialized configuration text.
	 * @param format Format of the supplied text.
	 * @return Unit on success or an error.
	 */
	suspend fun importApp(text: String, format: ConfigFormat): OperationResult<Unit> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.InvalidConfiguration(
				message = "No serializer registered for format $format"
			).left()
		return serializer.deserializeApp(text).fold(
			ifLeft = { it.left() },
			ifRight = { configRepository.saveConfig(it) }
		)
	}
	
	/**
	 * Export only the global configuration section in the given format.
	 *
	 * @param format Target serialization format.
	 * @return Serialized global configuration text or an error.
	 */
	suspend fun exportGlobal(format: ConfigFormat): OperationResult<String> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.InvalidConfiguration(
				message = "No serializer registered for format $format"
			).left()
		val config = configRepository.getCurrentConfig()
		return serializer.serializeGlobal(config.global)
	}
	
	/**
	 * Import and replace the global configuration section from serialized text.
	 * All other configuration sections (profiles, renewal jobs, etc.) are preserved.
	 *
	 * @param text Serialized global configuration text.
	 * @param format Format of the supplied text.
	 * @return Unit on success or an error.
	 */
	suspend fun importGlobal(text: String, format: ConfigFormat): OperationResult<Unit> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.InvalidConfiguration(
				message = "No serializer registered for format $format"
			).left()
		return serializer.deserializeGlobal(text).fold(
			ifLeft = { it.left() },
			ifRight = { global ->
				val current = configRepository.getCurrentConfig()
				configRepository.saveConfig(current.copy(global = global))
			}
		)
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
			?: return ConfigurationError.InvalidConfiguration(
				message = "No serializer registered for format $format"
			).left()
		val config = configRepository.getCurrentConfig()
		val profile = config.profiles[profileName]
			?: return ConfigurationError.InvalidConfiguration(
				message = "Profile '$profileName' does not exist"
			).left()
		return serializer.serializeProfile(profile)
	}
	
	/**
	 * Import a profile from serialized text and upsert it into the stored configuration.
	 * If [overrideName] is provided, it replaces the name embedded in the serialized profile.
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
			?: return ConfigurationError.InvalidConfiguration(
				message = "No serializer registered for format $format"
			).left()
		return serializer.deserializeProfile(text).fold(
			ifLeft = { it.left() },
			ifRight = { profile ->
				val finalProfile = if (overrideName != null) profile.copy(name = overrideName) else profile
				val current = configRepository.getCurrentConfig()
				val updated = current.copy(
					profiles = current.profiles + (finalProfile.name to finalProfile)
				)
				configRepository.saveConfig(updated).map { finalProfile.name }
			}
		)
	}
}

