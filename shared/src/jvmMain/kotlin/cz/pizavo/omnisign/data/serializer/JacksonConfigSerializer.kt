package cz.pizavo.omnisign.data.serializer

import arrow.core.Either
import arrow.core.getOrElse
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
import tools.jackson.databind.JsonNode
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
 * settings or a profile. Its properties tell which, and whether it holds one at all, as [kindOf]
 * describes.
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

	/**
	 * The property names [mapper] writes for each class of configuration a document can hold, and
	 * for the class of each section within them.
	 */
	private val classProperties: Map<Class<*>, Set<String>>

	/** The sections of each class in [classProperties], by the name of the property that holds them. */
	private val classSections: Map<Class<*>, Map<String, Section>>

	/** The top-level properties of a full configuration, which neither other kind has. */
	private val appMarkers: Set<String>

	/** The top-level properties of a profile that neither other kind has. */
	private val profileMarkers: Set<String>

	/** The top-level properties of the global settings, which a full configuration does not have. */
	private val globalMarkers: Set<String>

	init {
		val properties = mutableMapOf<Class<*>, Set<String>>()
		val sections = mutableMapOf<Class<*>, Map<String, Section>>()
		KIND_CLASSES.values.forEach { learn(it, properties, sections) }
		classProperties = properties.filterValues { it.isNotEmpty() }
		classSections = sections.filterKeys { it in classProperties }
		val app = classProperties.getValue(AppConfig::class.java)
		val global = classProperties.getValue(GlobalConfig::class.java)
		val profile = classProperties.getValue(ProfileConfig::class.java)
		appMarkers = app - global - profile
		profileMarkers = profile - global - app
		globalMarkers = global - app
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
	 * therefore read as a tree first, and bound only when it holds the [expected] kind, as [kindOf]
	 * tells. A document whose root is not an object is left to the binding, which fails on it or
	 * reads it as null.
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
			val found = kindOf(document).getOrElse { details ->
				return ConfigurationError.documentKindMismatch(expected, null, details).left()
			}
			if (found != expected) return ConfigurationError.documentKindMismatch(expected, found, null).left()
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
	 * The kind of configuration [document] holds, or the details of an error that says why it holds
	 * none.
	 *
	 * A full configuration shares no top-level property with the other two kinds, so any of its own
	 * marks one. A profile shares most of its properties with the global settings, so only those the
	 * global settings lack mark it, such as the `name` every profile has. Any other property of the
	 * global settings marks those.
	 *
	 * The document holds the kind it is marked as only when it passes three checks:
	 * - Its top-level properties all belong to the kind, or most of them do and one of those is
	 *   [specific], since a single lowercase word such as `name`, `description` or `global` is common
	 *   in other files, a `package.json` among them. So a file a newer build wrote, with top-level
	 *   properties this build does not know, reads only while they are fewer than the ones it knows.
	 * - None of its sections, such as the `global` of a full configuration or the `validation` of the
	 *   global settings, holds only properties the section does not have, as [foreignSection] tells:
	 *   the `global` of a Prometheus or Helm file holds none of the global settings' properties.
	 * - The top-level properties it shares with the kind do not all hold nothing, as [holdsNothing]
	 *   tells, so a document such as `{"profiles": {}}` holds no configuration.
	 *
	 * Lists are not looked into, nor what a property that is not a section holds.
	 */
	private fun kindOf(document: ObjectNode): Either<String, ConfigDocumentKind> {
		val names = namesOf(document)
		if (names.isEmpty()) return NO_NAMED_PROPERTIES.left()
		val kind = when {
			names.any { it in appMarkers } -> ConfigDocumentKind.APP
			names.any { it in profileMarkers } -> ConfigDocumentKind.PROFILE
			names.any { it in globalMarkers } -> ConfigDocumentKind.GLOBAL
			else -> return listed("Top-level properties: ", names).left()
		}
		val type = KIND_CLASSES.getValue(kind)
		val (known, unknown) = names.partition { it in classProperties.getValue(type) }
		if (unknown.isNotEmpty() && !(known.any { specific(it) } && known.size > unknown.size)) {
			val lacking = when (kind) {
				ConfigDocumentKind.APP -> "a full configuration does not have"
				ConfigDocumentKind.GLOBAL -> "the global settings do not have"
				ConfigDocumentKind.PROFILE -> "a profile does not have"
			}
			return listed("Top-level properties $lacking: ", unknown).left()
		}
		foreignSection(document, type, prefix = "")?.let { return it.left() }
		if (known.all { holdsNothing(document.get(it)) }) {
			return listed("Top-level properties that hold nothing: ", known).left()
		}
		return kind.right()
	}

	/**
	 * Whether the property [name] has a capital letter, as a camelCase name that joins several words,
	 * such as `defaultHashAlgorithm`, does. Other files use such names less often than a single
	 * lowercase word such as `name` or `global`.
	 */
	private fun specific(name: String): Boolean = name.any { it.isUpperCase() }

	/**
	 * The details of an error for the first section within [node], an object [type] binds, that holds
	 * properties but none the section has, or `null` when no section of it does so. [prefix] is the
	 * path to [node] in the document, to name the section by.
	 *
	 * A section that holds no property is left alone, as the configuration file the app keeps writes
	 * an override equal to the defaults as an empty object. One with properties this build does not
	 * know reads while it also holds one it knows, so that a newer build can add some.
	 */
	private fun foreignSection(node: ObjectNode, type: Class<*>, prefix: String): String? {
		for ((name, section) in classSections.getValue(type)) {
			val value = node.get(name) as? ObjectNode ?: continue
			val parts = if (section.inMap) {
				value.properties().mapNotNull { (key, part) -> (part as? ObjectNode)?.let { "$prefix$name.$key" to it } }
			} else {
				listOf("$prefix$name" to value)
			}
			for ((path, part) in parts) {
				val partNames = namesOf(part)
				if (partNames.isNotEmpty() && partNames.none { it in classProperties.getValue(section.type) }) {
					return listed("$path holds only properties it does not have in a configuration: ", partNames)
				}
				foreignSection(part, section.type, "$path.")?.let { return it }
			}
		}
		return null
	}

	/**
	 * Whether [node] holds nothing: it is missing or null, blank text, which is what XML gives an
	 * empty element, or a list or an object with nothing but such values in it.
	 */
	private fun holdsNothing(node: JsonNode?): Boolean = when {
		node == null || node.isNull || node.isMissingNode -> true
		node.isString -> node.stringValue().isBlank()
		node.isContainer -> node.values().all { holdsNothing(it) }
		else -> false
	}

	/**
	 * [names] after [prefix], for the details of an error that found no configuration in a document;
	 * at most the first [MAX_LISTED_PROPERTIES] are listed.
	 */
	private fun listed(prefix: String, names: List<String>): String =
		names.joinToString(prefix = prefix, limit = MAX_LISTED_PROPERTIES)

	/**
	 * The property names of [node] that are not blank. No configuration has a blank one, and Jackson
	 * gives the text an XML element holds beside its child elements an empty name.
	 */
	private fun namesOf(node: ObjectNode): List<String> =
		node.propertyNames().filter { it.isNotBlank() }

	/**
	 * Add the property names [mapper] writes for [type] to [properties], and its sections to
	 * [sections], then do the same for the class of each section; return whether [type] is written
	 * as an object with properties, as only the class of a section is.
	 *
	 * A property holds a section when its type, or the type of the values of the map it holds, is
	 * such a class. Lists are not counted, since XML writes them in a shape of its own.
	 */
	private fun learn(
		type: Class<*>,
		properties: MutableMap<Class<*>, Set<String>>,
		sections: MutableMap<Class<*>, Map<String, Section>>,
	): Boolean {
		properties[type]?.let { return it.isNotEmpty() }
		val described = describe(type)
		properties[type] = described.keys
		sections[type] = described.entries.mapNotNull { (name, declared) ->
			val section = when {
				declared.isMapLikeType -> Section(declared.contentType.rawClass, inMap = true)
				declared.isContainerType -> null
				else -> Section(declared.rawClass, inMap = false)
			}
			section?.takeIf { learn(it.type, properties, sections) }?.let { name to it }
		}.toMap()
		return described.isNotEmpty()
	}

	/**
	 * The properties [mapper] writes for [type], by name, with the type each one is declared with,
	 * read from the format visitor Jackson offers for describing a type, so that they follow every
	 * annotation and mixin the mapper applies. Empty for a type the mapper does not write as an object
	 * with properties, such as text, a number, a list or a map.
	 */
	private fun describe(type: Class<*>): Map<String, JavaType> {
		val properties = mutableMapOf<String, JavaType>()
		mapper.acceptJsonFormatVisitor(type, object : JsonFormatVisitorWrapper.Base() {
			override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor =
				object : JsonObjectFormatVisitor.Base(context) {
					override fun property(writer: BeanProperty) {
						properties[writer.name] = writer.type
					}

					override fun optionalProperty(writer: BeanProperty) {
						properties[writer.name] = writer.type
					}
				}
		})
		return properties
	}

	/** The error for a configuration [mapper] could not write, carrying [cause] and its message. */
	private fun serializeError(cause: Throwable): ConfigurationError.SaveFailed =
		ConfigurationError.serializeFailed(format.name, details = cause.message, cause = cause)

	/** The error for a document [mapper] could not read or bind, carrying [cause] and its message. */
	private fun deserializeError(cause: Throwable): ConfigurationError.LoadFailed =
		ConfigurationError.deserializeFailed(format.name, details = cause.message, cause = cause)

	private companion object {
		/** How many property names the details of an error that found no configuration list at most. */
		const val MAX_LISTED_PROPERTIES = 10

		/** The details of an error for a document none of whose top-level properties has a name. */
		const val NO_NAMED_PROPERTIES = "The document has no named top-level properties"

		/** The class each kind of configuration binds to. */
		val KIND_CLASSES: Map<ConfigDocumentKind, Class<*>> = mapOf(
			ConfigDocumentKind.APP to AppConfig::class.java,
			ConfigDocumentKind.GLOBAL to GlobalConfig::class.java,
			ConfigDocumentKind.PROFILE to ProfileConfig::class.java,
		)

		/**
		 * Where a property of a configuration holds a section: an object that binds to [type], or,
		 * when [inMap] is set, a map each of whose values does, as the profiles of a full
		 * configuration do.
		 *
		 * @property type The class the section binds to.
		 * @property inMap Whether the property holds a map of such sections rather than one.
		 */
		data class Section(val type: Class<*>, val inMap: Boolean)

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
