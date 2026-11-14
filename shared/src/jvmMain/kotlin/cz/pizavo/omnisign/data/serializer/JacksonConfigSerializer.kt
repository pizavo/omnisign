package cz.pizavo.omnisign.data.serializer

import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.annotation.JsonIgnore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.ConfigDocumentKind
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.port.ConfigSerializer
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.MapperBuilder
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/**
 * Base JVM serializer that delegates reading and writing to a Jackson [ObjectMapper].
 *
 * Subclasses provide a mapper builder configured for their format and the format label used in
 * error messages. A Jackson mapper cannot change once built, so this class takes the builder and adds
 * what every format shares: the Kotlin module, tolerance of unknown properties, the mixin that
 * keeps the runtime-only password out of an exported file, and [SensitiveJacksonSerializer], so that
 * a secret no mixin hides fails the export instead of landing in the file.
 *
 * A document is read only as the kind of configuration it holds: a full configuration, the global
 * settings or a profile. Its top-level properties tell which, as [kindOf] describes.
 *
 * @param mapperBuilder Builder of the Jackson [ObjectMapper], configured for the target format.
 */
abstract class JacksonConfigSerializer(
	mapperBuilder: MapperBuilder<*, *>
) : ConfigSerializer {
	/**
	 * The mapper every method here reads and writes with. Internal so the specs can check that it
	 * refuses to write a [Sensitive], which no configuration they can hand these methods carries visibly.
	 */
	internal val mapper: ObjectMapper = mapperBuilder
		.addModule(KotlinModule.Builder().build())
		.addModule(SimpleModule().addSerializer(Sensitive::class.java, SensitiveJacksonSerializer()))
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.addMixIn(TimestampServerConfig::class.java, TimestampServerConfigMixin::class.java)
		.build()

	/** The top-level properties of a full configuration, which neither other kind has. */
	private val appMarkers: Set<String>

	/** The top-level properties of a profile that neither other kind has. */
	private val profileMarkers: Set<String>

	/** The top-level properties of the global settings, which a full configuration does not have. */
	private val globalMarkers: Set<String>

	/** Every top-level property of each kind of configuration. */
	private val kindProperties: Map<ConfigDocumentKind, Set<String>>

	init {
		val app = propertyNames(AppConfig::class.java)
		val global = propertyNames(GlobalConfig::class.java)
		val profile = propertyNames(ProfileConfig::class.java)
		appMarkers = app - global - profile
		profileMarkers = profile - global - app
		globalMarkers = global - app
		kindProperties = mapOf(
			ConfigDocumentKind.APP to app,
			ConfigDocumentKind.GLOBAL to global,
			ConfigDocumentKind.PROFILE to profile,
		)
	}

	override fun serializeApp(config: AppConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(config) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeApp(text: String): OperationResult<AppConfig> =
		deserialize(text, AppConfig::class.java, ConfigDocumentKind.APP)
	
	override fun serializeGlobal(config: GlobalConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(config) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeGlobal(text: String): OperationResult<GlobalConfig> =
		deserialize(text, GlobalConfig::class.java, ConfigDocumentKind.GLOBAL)
	
	override fun serializeProfile(profile: ProfileConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(profile) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeProfile(text: String): OperationResult<ProfileConfig> =
		deserialize(text, ProfileConfig::class.java, ConfigDocumentKind.PROFILE)
	
	/**
	 * Bind [text] to [type], refusing a document of another kind than [expected] and a document that
	 * holds nothing but null.
	 *
	 * Unknown properties are ignored, so that a file written by a newer build still reads, and every
	 * property of a full configuration and of the global settings has a default, so a document of
	 * another kind would bind without an error, to the defaults: a global export read as a full
	 * configuration would replace every profile, renewal job and setting with them. The document is
	 * therefore read as a tree first, and bound only when its top-level properties mark it as the
	 * [expected] kind. A document whose root is not an object is left to the binding, which fails on
	 * it or reads it as null.
	 *
	 * Jackson reads a document that holds nothing but null, a JSON `null`, a YAML `~` or a bare `---`,
	 * or an XML root marked `xsi:nil`, as a null value rather than failing. Returned as a success, the
	 * null would break the import later, when it saved the configuration or merged the section into
	 * it, so it fails here like any other document that cannot be read.
	 *
	 * @param text The serialized configuration.
	 * @param type The class to bind [text] to.
	 * @param expected The kind of configuration [type] holds.
	 * @return The bound value; [ConfigurationError.InvalidConfiguration] when [text] holds a
	 *   configuration of another kind, or none; or [ConfigurationError.LoadFailed] when [text] cannot
	 *   be read or holds only null.
	 */
	private fun <T : Any> deserialize(text: String, type: Class<T>, expected: ConfigDocumentKind): OperationResult<T> {
		val document = runCatching { mapper.readTree(text) }.getOrElse { return deserializeError(it).left() }
		if (document is ObjectNode) {
			val found = kindOf(document)
			if (found != expected) {
				val details = if (found == null) propertiesFound(document) else null
				return ConfigurationError.documentKindMismatch(expected, found, details).left()
			}
		}
		return runCatching { mapper.readValue(text, type) }
			.fold(
				onSuccess = { value ->
					value?.right() ?: ConfigurationError.deserializeFailed(
						format.name,
						details = "The document holds no configuration, only null",
					).left()
				},
				onFailure = { deserializeError(it).left() },
			)
	}

	/**
	 * The kind of configuration [document] holds, as its top-level properties tell, or `null` when it
	 * holds none.
	 *
	 * A full configuration shares no top-level property with the other two kinds, so any of its own
	 * marks one. A profile shares most of its properties with the global settings, so only those the
	 * global settings lack mark it, such as the `name` every profile has. Any other property of the
	 * global settings marks those.
	 *
	 * A document holds the kind it is marked as when all its top-level properties belong to that
	 * kind. One with other properties too holds it only when most of its properties belong to the
	 * kind and one of those is [specific], since a single word such as `name`, `description` or
	 * `global` is common in other files, a `package.json` or a Prometheus configuration among them.
	 * So a file a newer build wrote, with top-level properties this build does not know, reads only
	 * while they are fewer than the ones this build knows. Only the names of the top-level properties
	 * are compared, not what they hold.
	 */
	private fun kindOf(document: ObjectNode): ConfigDocumentKind? {
		val names = topLevelNames(document)
		val kind = when {
			names.any { it in appMarkers } -> ConfigDocumentKind.APP
			names.any { it in profileMarkers } -> ConfigDocumentKind.PROFILE
			names.any { it in globalMarkers } -> ConfigDocumentKind.GLOBAL
			else -> return null
		}
		val known = names.filter { it in kindProperties.getValue(kind) }
		val holdsKind = known.size == names.size || (known.any { specific(it) } && known.size * 2 > names.size)
		return kind.takeIf { holdsKind }
	}

	/**
	 * Whether the property [name] is more than a single lowercase word: a compound such as
	 * `defaultHashAlgorithm`, which hardly any file but a configuration of this application has.
	 */
	private fun specific(name: String): Boolean = name.any { it.isUpperCase() }

	/**
	 * The top-level property names of [document], for the details of an error that found no
	 * configuration in it; at most the first [MAX_LISTED_PROPERTIES] are listed.
	 */
	private fun propertiesFound(document: ObjectNode): String =
		topLevelNames(document)
			.takeIf { it.isNotEmpty() }
			?.joinToString(prefix = "Top-level properties: ", limit = MAX_LISTED_PROPERTIES)
			?: "The document has no named top-level properties"

	/**
	 * The top-level property names of [document] that are not blank. No configuration has a blank
	 * one, and Jackson gives the text an XML root holds an empty name.
	 */
	private fun topLevelNames(document: ObjectNode): List<String> =
		document.propertyNames().filter { it.isNotBlank() }

	/**
	 * The top-level property names [mapper] writes for [type], read from the format visitor Jackson
	 * offers for describing a type, so that they follow every annotation and mixin the mapper applies.
	 */
	private fun propertyNames(type: Class<*>): Set<String> {
		val names = mutableSetOf<String>()
		mapper.acceptJsonFormatVisitor(type, object : JsonFormatVisitorWrapper.Base() {
			override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor =
				object : JsonObjectFormatVisitor.Base(context) {
					override fun property(writer: BeanProperty) {
						names += writer.name
					}

					override fun optionalProperty(writer: BeanProperty) {
						names += writer.name
					}
				}
		})
		return names
	}

	private fun serializeError(cause: Throwable): ConfigurationError.SaveFailed =
		ConfigurationError.serializeFailed(format.name, details = cause.message, cause = cause)

	private fun deserializeError(cause: Throwable): ConfigurationError.LoadFailed =
		ConfigurationError.deserializeFailed(format.name, details = cause.message, cause = cause)

	private companion object {
		/** How many property names the details of an error that found no configuration list at most. */
		const val MAX_LISTED_PROPERTIES = 10

		/**
		 * Jackson mixin that instructs Jackson to skip the runtime-only
		 * [TimestampServerConfig.runtimePassword] field, which is marked with
		 * `kotlinx.serialization.Transient` but that annotation is unknown to Jackson.
		 */
		abstract class TimestampServerConfigMixin {
			@get:JsonIgnore
			abstract val runtimePassword: Sensitive<String>?
		}
	}
}
