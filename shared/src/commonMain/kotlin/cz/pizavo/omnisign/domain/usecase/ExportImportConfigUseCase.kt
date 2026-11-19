package cz.pizavo.omnisign.domain.usecase

import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.disabledDefaultError
import cz.pizavo.omnisign.domain.model.config.disabledOverrideError
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.port.ConfigSerializer
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.repository.clearOrphanedProfileScopes
import cz.pizavo.omnisign.domain.repository.ownedProfileScopeNames

/**
 * Use case for exporting and importing application and profile configurations
 * in a variety of text formats (JSON, XML, YAML).
 *
 * Export operations read from the active configuration and produce a serialized string.
 * Import operations deserialize a string and merge the result into the stored configuration.
 *
 * An import that adds or drops a profile keeps the [TrustStore] in step, as [ManageProfileUseCase]
 * does: a profile's directly trusted certificates are kept under a scope keyed by its name alone, so
 * every scope no profile owns is dropped before a profile of a new name is saved, and again once a
 * profile is gone, as [clearOrphanedProfileScopes] describes.
 *
 * @param configRepository Store of the configuration that is exported and imported.
 * @param serializerRegistry Serializers of the supported text formats.
 * @param trustStore Store of the profiles' directly trusted certificates.
 */
class ExportImportConfigUseCase(
	private val configRepository: ConfigRepository,
	private val serializerRegistry: ConfigSerializerRegistry,
	private val trustStore: TrustStore,
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
	 * When the imported configuration names a profile the stored one does not, every trust scope no
	 * profile owns is dropped before it is saved, and when it lacks a stored profile, again after it is
	 * saved, so neither an imported profile nor one created later inherits the certificates of a profile
	 * that is gone. A failure before the save leaves the configuration as it was; one after it returns
	 * the error with the imported configuration saved. The stored profiles are read with
	 * [ConfigRepository.loadConfig], so a stored configuration that cannot be read fails the import
	 * before any scope is dropped. [ConfigRepository.getCurrentConfig] would fall back on the
	 * configuration last read, or on the defaults, with no profiles, when none was read, as in a fresh
	 * CLI process, and every stored profile's scope would then look orphaned.
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
		val stored = configRepository.loadConfig().getOrElse { return it.left() }.profiles.ownedProfileScopeNames()
		val imported = config.profiles.ownedProfileScopeNames()
		if (!stored.containsAll(imported)) {
			trustStore.clearOrphanedProfileScopes(stored).getOrElse { return it.left() }
		}
		configRepository.saveConfig(config).getOrElse { return it.left() }
		if (imported.containsAll(stored)) return Unit.right()
		return trustStore.clearOrphanedProfileScopes(imported)
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
	): OperationResult<String> =
		readProfile(text, format).flatMap { saveProfile(it, overrideName) }

	/**
	 * Read the profile serialized [text] holds, without saving it: the first half of [importProfile],
	 * for an import that has more to check before it saves.
	 *
	 * Text that holds a full configuration or the global settings instead, or no configuration, is
	 * refused, as [ConfigSerializer.deserializeProfile] describes.
	 *
	 * @param text Serialized profile text.
	 * @param format Format of the supplied text.
	 * @return The profile, or an error.
	 */
	fun readProfile(text: String, format: ConfigFormat): OperationResult<ProfileConfig> {
		val serializer = serializerRegistry.get(format)
			?: return ConfigurationError.noSerializerForFormat(format.name).left()
		return serializer.deserializeProfile(text)
	}

	/**
	 * Upsert [profile] into the stored configuration, renamed to [overrideName] when it is given:
	 * the second half of [importProfile].
	 *
	 * A profile whose hash or encryption override is among the algorithms it disables is refused and
	 * nothing is saved, as [disabledOverrideError] describes; the error names the profile as it would
	 * have been saved. Before a profile of a new name is saved, every trust scope no profile owns is
	 * dropped, as [clearOrphanedProfileScopes] describes, so it does not inherit the certificates of a
	 * removed profile of that name; when that fails, nothing is saved. The stored profiles are read with
	 * [ConfigRepository.loadConfig], so a stored configuration that cannot be read fails the import
	 * before any scope is dropped.
	 *
	 * @param profile The profile [readProfile] read.
	 * @param overrideName Optional name to use instead of the profile's own.
	 * @return The name under which the profile was saved, or an error.
	 */
	suspend fun saveProfile(profile: ProfileConfig, overrideName: String? = null): OperationResult<String> {
		val finalProfile = if (overrideName != null) profile.copy(name = overrideName) else profile
		finalProfile.disabledOverrideError()?.let { return it.left() }
		val current = configRepository.loadConfig().getOrElse { return it.left() }
		val owned = current.profiles.ownedProfileScopeNames()
		if (finalProfile.name !in owned) {
			trustStore.clearOrphanedProfileScopes(owned).getOrElse { return it.left() }
		}
		val updated = current.copy(
			profiles = current.profiles + (finalProfile.name to finalProfile)
		)
		return configRepository.saveConfig(updated).map { finalProfile.name }
	}
}

