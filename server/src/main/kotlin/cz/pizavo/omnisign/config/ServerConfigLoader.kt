package cz.pizavo.omnisign.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Loads [ServerConfig] from a YAML file.
 *
 * Resolution order:
 * 1. Explicit [path] argument (e.g. from a CLI flag).
 * 2. `OMNISIGN_SERVER_CONFIG` environment variable.
 * 3. `server.yml` in the current working directory.
 * 4. Classpath resource `/server.yml`.
 * 5. Built-in defaults ([ServerConfig] no-arg constructor).
 */
class ServerConfigLoader {

	private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
		.registerKotlinModule()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

	/**
	 * Load the server configuration from the first available source.
	 *
	 * @param path Optional explicit path to the YAML file.
	 * @return Parsed [ServerConfig], or a default instance when no config file is found.
	 */
	fun load(path: String? = null): ServerConfig {
		val file = resolveFile(path)
		if (file != null) {
			logger.info { "Loading server configuration from ${file.absolutePath}" }
			return mapper.readValue(file, ServerConfig::class.java)
		}

		val resource = javaClass.getResourceAsStream("/$DEFAULT_FILE_NAME")
		if (resource != null) {
			logger.info { "Loading server configuration from classpath resource /$DEFAULT_FILE_NAME" }
			return resource.use { mapper.readValue(it, ServerConfig::class.java) }
		}

		logger.info { "No server configuration found — using defaults" }
		return ServerConfig()
	}

	/**
	 * Resolve a filesystem config file from the explicit path, environment variable, or CWD.
	 */
	private fun resolveFile(explicitPath: String?): File? {
		if (explicitPath != null) {
			val f = File(explicitPath)
			if (f.isFile) return f
			logger.warn { "Explicit config path $explicitPath does not exist — falling back" }
		}

		val envPath = System.getenv(ENV_VAR)
		if (!envPath.isNullOrBlank()) {
			val f = File(envPath)
			if (f.isFile) return f
			logger.warn { "$ENV_VAR=$envPath does not exist — falling back" }
		}

		val cwd = File(DEFAULT_FILE_NAME)
		if (cwd.isFile) return cwd

		return null
	}

	companion object {
		private const val ENV_VAR = "OMNISIGN_SERVER_CONFIG"
		private const val DEFAULT_FILE_NAME = "server.yml"
	}
}

