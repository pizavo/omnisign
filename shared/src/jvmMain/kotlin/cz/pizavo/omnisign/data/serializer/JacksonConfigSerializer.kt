package cz.pizavo.omnisign.data.serializer

import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.port.ConfigSerializer

/**
 * Jackson mixin that instructs Jackson to skip the runtime-only [TimestampServerConfig.runtimePassword]
 * field, which is marked with `kotlinx.serialization.Transient` but that annotation is unknown to Jackson.
 */
private abstract class TimestampServerConfigMixin {
	@get:JsonIgnore
	abstract val runtimePassword: String?
}

/**
 * Base JVM serializer that delegates reading and writing to a Jackson [ObjectMapper].
 *
 * Subclasses provide the configured mapper and the format label used in error messages.
 *
 * @property mapper The Jackson [ObjectMapper] configured for the target format.
 */
abstract class JacksonConfigSerializer(
	protected val mapper: ObjectMapper
) : ConfigSerializer {
	init {
		mapper.registerModule(KotlinModule.Builder().build())
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		mapper.addMixIn(TimestampServerConfig::class.java, TimestampServerConfigMixin::class.java)
	}
	
	override fun serializeApp(config: AppConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(config) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeApp(text: String): OperationResult<AppConfig> =
		runCatching { mapper.readValue(text, AppConfig::class.java) }
			.fold(onSuccess = { it.right() }, onFailure = { deserializeError(it).left() })
	
	override fun serializeGlobal(config: GlobalConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(config) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeGlobal(text: String): OperationResult<GlobalConfig> =
		runCatching { mapper.readValue(text, GlobalConfig::class.java) }
			.fold(onSuccess = { it.right() }, onFailure = { deserializeError(it).left() })
	
	override fun serializeProfile(profile: ProfileConfig): OperationResult<String> =
		runCatching { mapper.writeValueAsString(profile) }
			.fold(onSuccess = { it.right() }, onFailure = { serializeError(it).left() })
	
	override fun deserializeProfile(text: String): OperationResult<ProfileConfig> =
		runCatching { mapper.readValue(text, ProfileConfig::class.java) }
			.fold(onSuccess = { it.right() }, onFailure = { deserializeError(it).left() })
	
	private fun serializeError(cause: Throwable): ConfigurationError.SaveFailed =
		ConfigurationError.SaveFailed(
			message = "Failed to serialize configuration to ${format.name}",
			details = cause.message,
			cause = cause
		)
	
	private fun deserializeError(cause: Throwable): ConfigurationError.LoadFailed =
		ConfigurationError.LoadFailed(
			message = "Failed to deserialize configuration from ${format.name}",
			details = cause.message,
			cause = cause
		)
}
