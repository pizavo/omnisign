package cz.pizavo.omnisign.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Jackson mixin for [TimestampServerConfig] when loading the server's signing.yml.
 *
 * Exposes the in-memory [TimestampServerConfig.runtimePassword] under the YAML key
 * `password`, so a provider supplies the TSA HTTP Basic password from the environment
 * (`password: "${OMNISIGN_TSA_PASSWORD}"`). The value is a [Sensitive] held only in memory
 * (the field is `@Transient` for kotlinx serialization, so it is never written to disk),
 * which suits a headless server where the OS-keyring `credentialKey` path is unavailable.
 */
private abstract class TimestampServerConfigMixin {
	@get:JsonProperty("password")
	abstract val runtimePassword: Sensitive<String>?
}

/**
 * Loads the provider's [SigningConfig] from `signing.yml` (or a `.json` equivalent) and folds
 * it into an [AppConfig] for the read-only server.
 *
 * **Format.** YAML is primary; a file whose name ends in `.json` is parsed as JSON. Both
 * share the same strict mapper, so a provider can build its own abstraction around the JSON
 * representation.
 *
 * **Environment substitution.** The document is parsed to a tree first, then every `${NAME}`
 * placeholder inside a string *value* is expanded from the environment via [substituteEnvVars]
 * (keys and comments are never touched, because comments are already gone after parsing); an
 * unset variable referenced by a value fails fast. Any value can therefore come from the
 * environment, including the TSA password (`password: "${OMNISIGN_TSA_PASSWORD}"`, see
 * [TimestampServerConfigMixin]) — so a secret is referenced by the file but never stored in it.
 *
 * **Strict parsing.** [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES] is enabled, so any
 * key that does not map to a property — including the `AppConfig` fields excluded from the
 * provider schema (`activeProfile`, `tlDrafts`, `renewalJobs`, `schedulerConfig`) — fails
 * startup rather than being silently ignored.
 *
 * **TSA credentials.** The OS-keyring `credentialKey` is rejected: a headless server has no
 * keyring, so the password is supplied from the environment via `password` instead.
 *
 * **Profile assembly.** [ProfileSources.inline], every file in [ProfileSources.files], and
 * every `*.yml` / `*.yaml` file found by recursively scanning each [ProfileSources.directories]
 * entry (hidden entries skipped) are combined into a single map keyed by [ProfileConfig.name].
 * A name appearing twice from any source is a hard error. Relative `files` / `directories`
 * paths resolve against the directory containing the signing.yml file.
 *
 * **Fail-fast.** A configured-but-missing file, a malformed/unknown-key document, a missing
 * referenced profile file or directory, a duplicate profile name, an unset environment
 * variable referenced by a value, or a `credentialKey` all throw, so a misconfiguration stops
 * the server at startup instead of surfacing on the first request.
 *
 * @param env Environment-variable resolver, injectable for testing. Defaults to the process
 *   environment.
 */
class SigningConfigLoader(private val env: (String) -> String? = System::getenv) {

	private val yamlMapper: ObjectMapper = configure(ObjectMapper(YAMLFactory()))
	private val jsonMapper: ObjectMapper = configure(ObjectMapper())

	/**
	 * Apply the shared strict configuration to [mapper]: the Kotlin module, fail-on-unknown
	 * keys, the [TimestampServerConfigMixin], and the [Sensitive] string deserializer.
	 */
	private fun configure(mapper: ObjectMapper): ObjectMapper =
		mapper.registerKotlinModule()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
			.addMixIn(TimestampServerConfig::class.java, TimestampServerConfigMixin::class.java)
			.registerModule(
				SimpleModule().addDeserializer(
					Sensitive::class.java,
					@Suppress("UNCHECKED_CAST")
					(SensitiveStringJacksonDeserializer() as JsonDeserializer<Sensitive<*>>),
				),
			)

	/**
	 * Load and resolve the signing configuration referenced by [path].
	 *
	 * @param path Filesystem path from `server.yml`'s `signingConfigFile`, or `null` when the
	 *   operator configured none.
	 * @return The resolved [AppConfig]. When [path] is `null`, built-in defaults with no
	 *   profiles are returned (with a WARN); the home-directory config file is never read.
	 * @throws IllegalArgumentException if [path] is set but missing/invalid, a referenced
	 *   profile file or directory is missing, two profiles share a name, or a `credentialKey`
	 *   is present. [IllegalStateException] if a value references an unset environment variable.
	 *   Jackson throws on a malformed document or an unknown key.
	 */
	fun load(path: String?): AppConfig {
		if (path == null) {
			logger.warn {
				"No signingConfigFile configured — using built-in signing defaults with no " +
					"profiles. Set signingConfigFile in server.yml to provide a signing/validation policy."
			}
			return AppConfig(global = GlobalConfig())
		}
		val file = File(path)
		require(file.isFile) {
			"signingConfigFile '$path' does not exist or is not a file. Provide a valid " +
				"signing.yml path or remove signingConfigFile from server.yml."
		}
		logger.info { "Loading signing configuration from ${file.absolutePath}" }
		return assemble(parse(file, SigningConfig::class.java), file.parentFile ?: File("."))
	}

	/**
	 * Parse [file] (by extension) into [type], expanding `${NAME}` environment placeholders in
	 * string values only.
	 *
	 * The document is read into a JSON tree first; expansion runs over the tree's string
	 * values (so YAML comments, already discarded by the parser, are never substituted); the
	 * tree is then bound to [type] under the strict mapper.
	 */
	private fun <T> parse(file: File, type: Class<T>): T {
		val mapper = mapperFor(file)
		val tree = mapper.readTree(file.readText(Charsets.UTF_8))
		substituteTreeValues(tree)
		return mapper.treeToValue(tree, type)
	}

	/**
	 * Recursively expand `${NAME}` environment placeholders in every string value of [node],
	 * leaving object keys, numbers, and booleans untouched.
	 */
	private fun substituteTreeValues(node: JsonNode) {
		when (node) {
			is ObjectNode -> node.fieldNames().asSequence().toList().forEach { name ->
				when (val child = node.get(name)) {
					is TextNode -> node.put(name, substituteEnvVars(child.asText(), env))
					is ObjectNode -> substituteTreeValues(child)
					is ArrayNode -> substituteTreeValues(child)
					else -> {}
				}
			}

			is ArrayNode -> for (i in 0 until node.size()) {
				when (val child = node.get(i)) {
					is TextNode -> node.set(i, TextNode.valueOf(substituteEnvVars(child.asText(), env)))
					is ObjectNode -> substituteTreeValues(child)
					is ArrayNode -> substituteTreeValues(child)
					else -> {}
				}
			}

			else -> {}
		}
	}

	/**
	 * Fold [signingConfig] into an [AppConfig], resolving and merging every profile source
	 * relative to [baseDir], then reject any unsupported keyring credential.
	 *
	 * @param signingConfig Parsed provider schema.
	 * @param baseDir Directory the relative `files` / `directories` paths resolve against.
	 * @return The assembled [AppConfig] with profiles keyed by [ProfileConfig.name].
	 */
	private fun assemble(signingConfig: SigningConfig, baseDir: File): AppConfig {
		val profiles = LinkedHashMap<String, ProfileConfig>()

		fun register(profile: ProfileConfig, source: String) {
			val previous = profiles.put(profile.name, profile)
			require(previous == null) {
				"Duplicate profile name '${profile.name}' (from $source). Profile names must be " +
					"unique across inline, files, and directories."
			}
		}

		signingConfig.profiles.inline.forEach { register(it, "inline") }

		signingConfig.profiles.files.forEach { entry ->
			val file = resolve(baseDir, entry)
			require(file.isFile) {
				"Profile file '$entry' (resolved to ${file.path}) does not exist or is not a file."
			}
			register(parseProfile(file), "file ${file.path}")
		}

		signingConfig.profiles.directories
			.map { resolve(baseDir, it).canonicalFile }
			.distinct()
			.forEach { dir ->
				require(dir.isDirectory) {
					"Profile directory '${dir.path}' does not exist or is not a directory."
				}
				dir.walkTopDown()
					.onEnter { it == dir || !it.name.startsWith(".") }
					.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in PROFILE_EXTENSIONS }
					.forEach { register(parseProfile(it), "directory ${dir.path}") }
			}

		val appConfig = AppConfig(global = signingConfig.global, profiles = profiles)
		rejectKeyringCredentials(appConfig)
		return appConfig
	}

	/**
	 * Reject the OS-keyring `credentialKey` on any timestamp-server config: a headless server
	 * has no keyring, so the TSA password must come from the environment via `password`.
	 *
	 * @param config The assembled configuration to check.
	 */
	private fun rejectKeyringCredentials(config: AppConfig) {
		requireNoCredentialKey(config.global.timestampServer, "global")
		config.profiles.values.forEach { requireNoCredentialKey(it.timestampServer, "profile '${it.name}'") }
	}

	/**
	 * Throw when [timestampServer] carries a [TimestampServerConfig.credentialKey].
	 *
	 * @param timestampServer The TSA config to check, or `null`.
	 * @param source Human-readable origin used in the error message.
	 */
	private fun requireNoCredentialKey(timestampServer: TimestampServerConfig?, source: String) {
		require(timestampServer?.credentialKey == null) {
			"timestampServer.credentialKey (OS keyring lookup) is not supported on the server " +
				"($source). Supply the TSA password from the environment via " +
				"password: \"\${ENV_VAR}\" instead."
		}
	}

	/**
	 * Parse a single bare [ProfileConfig] from [file], choosing the mapper by extension and
	 * expanding environment placeholders in its values.
	 */
	private fun parseProfile(file: File): ProfileConfig = parse(file, ProfileConfig::class.java)

	/**
	 * Select the JSON mapper for a `.json` file and the YAML mapper otherwise.
	 */
	private fun mapperFor(file: File): ObjectMapper =
		if (file.extension.equals("json", ignoreCase = true)) jsonMapper else yamlMapper

	/**
	 * Resolve [path] against [baseDir] when relative, or return it unchanged when absolute.
	 */
	private fun resolve(baseDir: File, path: String): File {
		val file = File(path)
		return if (file.isAbsolute) file else File(baseDir, path)
	}

	companion object {
		/**
		 * File extensions scanned inside a [ProfileSources.directories] entry.
		 */
		private val PROFILE_EXTENSIONS = setOf("yml", "yaml")
	}
}
