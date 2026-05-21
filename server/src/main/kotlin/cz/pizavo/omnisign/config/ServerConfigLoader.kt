package cz.pizavo.omnisign.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import cz.pizavo.omnisign.domain.model.value.Sensitive
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
 *
 * **Environment variable substitution.** Before parsing, any `${NAME}` placeholder in the
 * YAML text is replaced with the value of the corresponding environment variable. The
 * variable name must match `[A-Z_][A-Z0-9_]*` (uppercase, digits, underscores). This is
 * how secrets enter the config without ever being written to the YAML file itself —
 * e.g., `secret: "${OMNISIGN_JWT_SECRET}"` reads the secret from the environment at
 * load time. Missing referenced env vars cause load to fail with a clear error.
 *
 * **Strict unknown-key parsing.** Jackson is configured with
 * [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES] enabled so any YAML key that does
 * not map to a property on the corresponding model class causes startup to fail with
 * `UnrecognizedPropertyException` naming the offending key. The motivation is purely
 * security: a silent ignore turns typos in security-critical keys into invisibly
 * disabled defenses — `auth: { enable: true }` (missing the trailing `d`) would parse
 * as "auth block present but `enabled` defaults to false" and leave every route open;
 * `tls: { keystorePat: "..." }` would skip TLS entirely; etc. Failing loudly forces
 * every misconfiguration through an explicit fix instead of through unnoticed
 * fail-open behavior.
 */
class ServerConfigLoader {

	private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
		.registerKotlinModule()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
		.registerModule(
			SimpleModule()
				.addDeserializer(SsoProviderConfig::class.java, SsoProviderConfigDeserializer())
				.addDeserializer(
					Sensitive::class.java,
					@Suppress("UNCHECKED_CAST")
					(SensitiveStringJacksonDeserializer() as JsonDeserializer<Sensitive<*>>),
				),
		)

	/**
	 * Load the server configuration from the first available source.
	 *
	 * @param path Optional explicit path to the YAML file.
	 * @return Parsed [ServerConfig], or a default instance when no config file is found.
	 */
	fun load(path: String? = null): ServerConfig {
		val rawYaml = readYamlText(path)
		if (rawYaml == null) {
			logger.info { "No server configuration found — using defaults" }
			return ServerConfig()
		}
		val expanded = substituteEnvVars(rawYaml)
		return mapper.readValue(expanded, ServerConfig::class.java)
	}

	/**
	 * Resolve and read the YAML text from the first available source, returning `null` when
	 * none is found so callers can fall back to defaults.
	 */
	private fun readYamlText(explicitPath: String?): String? {
		val file = resolveFile(explicitPath)
		if (file != null) {
			logger.info { "Loading server configuration from ${file.absolutePath}" }
			return file.readText(Charsets.UTF_8)
		}

		val resource = javaClass.getResourceAsStream("/$DEFAULT_FILE_NAME")
		if (resource != null) {
			logger.info { "Loading server configuration from classpath resource /$DEFAULT_FILE_NAME" }
			return resource.use { it.bufferedReader(Charsets.UTF_8).readText() }
		}

		return null
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

	/**
	 * Load the server configuration from a raw YAML string.
	 *
	 * Primarily intended for testing so that a config can be provided inline without a file.
	 * Env var substitution is applied here too, matching production behavior.
	 *
	 * @param yaml YAML content to parse.
	 * @return Parsed [ServerConfig].
	 */
	fun loadFromString(yaml: String): ServerConfig =
		mapper.readValue(substituteEnvVars(yaml), ServerConfig::class.java)

	/**
	 * Replace every `${NAME}` placeholder in [yaml] with the value of the corresponding
	 * environment variable.
	 *
	 * The substitution is textual and runs before YAML parsing. Variable names must match
	 * `[A-Z_][A-Z0-9_]*`. A missing env var causes a clear startup failure naming the variable.
	 *
	 * @throws IllegalStateException if a referenced env var is not set.
	 */
	private fun substituteEnvVars(yaml: String): String =
		ENV_VAR_PATTERN.replace(yaml) { match ->
			val varName = match.groupValues[1]
			val value = System.getenv(varName)
				?: error(
					$$"server.yml references environment variable '${$$varName}' but it is not set. " +
							"Set $varName before starting the server, or remove the reference from the YAML.",
				)
			value
		}

	companion object {
		private const val ENV_VAR = "OMNISIGN_SERVER_CONFIG"
		private const val DEFAULT_FILE_NAME = "server.yml"
		private val ENV_VAR_PATTERN = Regex("""\$\{([A-Z_][A-Z0-9_]*)}""")
	}
}

