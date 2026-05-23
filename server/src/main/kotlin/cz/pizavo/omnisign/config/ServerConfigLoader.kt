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
 * 4. Built-in defaults ([ServerConfig] no-arg constructor) — with a WARN telling the
 *    operator how to find the bundled `server.example.yml` template.
 *
 * **No classpath fallback.** Earlier revisions silently loaded a bundled `/server.yml`
 * resource from the JAR when none of the file paths matched. That collapsed three
 * distinct operator intents — "I want defaults", "I want the shipped example", "I
 * forgot to create my config" — into the same fail-open runtime, and the example
 * happened to ship with `development: true` plus other dev-friendly values. The
 * resource is now named `server.example.yml`, lives in the JAR purely as
 * documentation (`jar xf <jar> server.example.yml` extracts it), and the loader does
 * not consult it. Operators who run the JAR without any of paths (1)–(3) get Kotlin
 * defaults and the validators in `moduleWith` decide whether to start.
 *
 * **Environment variable substitution.** The YAML is parsed to a tree, then any `${NAME}`
 * placeholder in a string value is replaced (keys and comments are never substituted). The
 * variable name must match `[A-Z_][A-Z0-9_]*` (uppercase, digits, underscores). This is
 * how a small number of non-secret config values (e.g. an external base URL) can come
 * from the environment. Operator secrets are not loaded this way — see [ServerSecrets].
 * Missing referenced env vars cause load to fail with a clear error.
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
class ServerConfigLoader(private val env: (String) -> String? = System::getenv) {

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
		.addHandler(RemovedSecretFieldHandler())

	/**
	 * Load the server configuration from the first available source.
	 *
	 * @param path Optional explicit path to the YAML file.
	 * @return Parsed [ServerConfig], or a default instance when no config file is found.
	 */
	fun load(path: String? = null): ServerConfig {
		val rawYaml = readYamlText(path)
		if (rawYaml == null) {
			logger.warn {
				"No server.yml found — using built-in Kotlin defaults, which require an " +
					"explicit cors: block AND either host: 127.0.0.1 (loopback) or a " +
					"tls:/proxy: block before the server will start. Copy server.example.yml " +
					"(bundled in this JAR; extract with `jar xf <jar> server.example.yml`) " +
					"to server.yml in the working directory, set $ENV_VAR to its path, or " +
					"pass --config <path>."
			}
			return ServerConfig()
		}
		val tree = mapper.readTree(rawYaml)
		substituteTreeValues(tree, env)
		return mapper.treeToValue(tree, ServerConfig::class.java)
	}

	/**
	 * Resolve and read the YAML text from the first filesystem source, returning `null`
	 * when none is found so [load] can fall back to Kotlin defaults.
	 *
	 * Intentionally does NOT consult the JAR's bundled `/server.example.yml` resource —
	 * the bundled file is documentation, never auto-loaded. See the class-level KDoc.
	 */
	private fun readYamlText(explicitPath: String?): String? {
		val file = resolveFile(explicitPath) ?: return null
		logger.info { "Loading server configuration from ${file.absolutePath}" }
		return file.readText(Charsets.UTF_8)
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
	fun loadFromString(yaml: String): ServerConfig {
		val tree = mapper.readTree(yaml)
		substituteTreeValues(tree, env)
		return mapper.treeToValue(tree, ServerConfig::class.java)
	}

	companion object {
		private const val ENV_VAR = "OMNISIGN_SERVER_CONFIG"
		private const val DEFAULT_FILE_NAME = "server.yml"
	}
}

