package cz.pizavo.omnisign.data.serializer

import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.annotation.JsonIgnore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.port.ConfigSerializer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.MapperBuilder
import tools.jackson.databind.module.SimpleModule
import tools.jackson.module.kotlin.KotlinModule

/**
 * Jackson mixin that instructs Jackson to skip the runtime-only [TimestampServerConfig.runtimePassword]
 * field, which is marked with `kotlinx.serialization.Transient` but that annotation is unknown to Jackson.
 */
private abstract class TimestampServerConfigMixin {
	@get:JsonIgnore
	abstract val runtimePassword: Sensitive<String>?
}

/**
 * Base JVM serializer that delegates reading and writing to a Jackson [ObjectMapper].
 *
 * Subclasses provide a mapper builder configured for their format and the format label used in
 * error messages. A Jackson mapper cannot change once built, so this class takes the builder and adds
 * what every format shares: the Kotlin module, tolerance of unknown properties, the mixin that
 * keeps the runtime-only password out of an exported file, and [SensitiveJacksonSerializer], so that
 * a secret no mixin hides fails the export instead of landing in the file.
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

	override fun serializeApp(config: AppConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(config) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeApp(text: String): OperationResult<AppConfig> =
		deserialize(text, AppConfig::class.java)
	
	override fun serializeGlobal(config: GlobalConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(config) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeGlobal(text: String): OperationResult<GlobalConfig> =
		deserialize(text, GlobalConfig::class.java)
	
	override fun serializeProfile(profile: ProfileConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(profile) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeProfile(text: String): OperationResult<ProfileConfig> =
		deserialize(text, ProfileConfig::class.java)
	
	/**
	 * Bind [text] to [type], refusing a document that holds nothing but null.
	 *
	 * Jackson reads such a document, a JSON `null`, a YAML `~` or a bare `---`, or an XML root marked
	 * `xsi:nil`, as a null value rather than failing. Returned as a success, the null would break the
	 * import later, when it saved the configuration or merged the section into it, so it fails here
	 * like any other document that cannot be read.
	 *
	 * @param text The serialized configuration.
	 * @param type The class to bind [text] to.
	 * @return The bound value, or [ConfigurationError.LoadFailed] when [text] cannot be read or holds only null.
	 */
	private fun <T : Any> deserialize(text: String, type: Class<T>): OperationResult<T> =
		runCatching { mapper.readValue(text, type) }
			.fold(
				onSuccess = { value ->
					value?.right() ?: ConfigurationError.deserializeFailed(
						format.name,
						details = "The document holds no configuration, only null",
					).left()
				},
				onFailure = { deserializeError(it).left() },
			)

	private fun serializeError(cause: Throwable): ConfigurationError.SaveFailed =
		ConfigurationError.serializeFailed(format.name, details = cause.message, cause = cause)

	private fun deserializeError(cause: Throwable): ConfigurationError.LoadFailed =
		ConfigurationError.deserializeFailed(format.name, details = cause.message, cause = cause)
}
