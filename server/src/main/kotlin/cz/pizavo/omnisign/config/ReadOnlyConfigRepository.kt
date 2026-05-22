package cz.pizavo.omnisign.config

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository

/**
 * Read-only [ConfigRepository] backed by an [AppConfig] resolved once at startup from the
 * provider's `signing.yml` (see [SigningConfigLoader]).
 *
 * The server is read-only to its clients: the signing/validation policy is fixed at deploy
 * time, so [saveConfig] always fails and [loadConfig] / [getCurrentConfig] return the
 * immutable startup configuration. This binding replaces the desktop home-directory
 * [cz.pizavo.omnisign.data.repository.FileConfigRepository] — which writes a default file and
 * reads from the user's home directory — for the server process.
 *
 * @property config The fixed application configuration loaded at startup.
 */
class ReadOnlyConfigRepository(private val config: AppConfig) : ConfigRepository {

	/**
	 * Return the fixed startup configuration. Never fails.
	 */
	override suspend fun loadConfig(): OperationResult<AppConfig> = config.right()

	/**
	 * Reject the save: server signing configuration is fixed at startup and cannot be
	 * mutated at runtime.
	 *
	 * @param config Ignored.
	 * @return A [ConfigurationError.SaveFailed] describing the read-only contract.
	 */
	override suspend fun saveConfig(config: AppConfig): OperationResult<Unit> =
		ConfigurationError.SaveFailed(
			message = "Server signing configuration is read-only",
			details = "The server loads its signing/validation policy from signing.yml at " +
				"startup; runtime changes are not supported.",
		).left()

	/**
	 * Return the fixed startup configuration.
	 */
	override suspend fun getCurrentConfig(): AppConfig = config
}
