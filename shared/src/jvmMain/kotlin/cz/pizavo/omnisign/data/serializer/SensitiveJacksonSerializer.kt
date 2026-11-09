package cz.pizavo.omnisign.data.serializer

import cz.pizavo.omnisign.domain.model.value.Sensitive
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.DatabindException
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer

/**
 * Jackson serializer for [Sensitive] that refuses to write one, by throwing [DatabindException].
 *
 * It is the Jackson counterpart of
 * [SensitiveSerializer][cz.pizavo.omnisign.domain.model.value.SensitiveSerializer]. Jackson reflects over
 * properties and never consults a kotlinx serializer, so a mapper without this one writes a [Sensitive]
 * as `{"value": …}`, the secret in plain text. Every mapper that handles OmniSign's configuration
 * registers it: [JacksonConfigSerializer] for exported files, and the server's `ServerConfigLoader` and
 * `SigningConfigLoader`, which only read. A secret that a property forgot to hide then fails the write
 * instead of leaking. A property meant to stay out of the output still needs `@JsonIgnore`, as
 * [JacksonConfigSerializer]'s mixin gives `runtimePassword`.
 *
 * The exception names the path to the property, which Jackson adds, but never the value. A `null`
 * [Sensitive] is written as `null` without reaching this serializer.
 */
class SensitiveJacksonSerializer : ValueSerializer<Sensitive<*>>() {

	/** Always throws [DatabindException]. */
	override fun serialize(value: Sensitive<*>, generator: JsonGenerator, context: SerializationContext) {
		throw DatabindException.from(
			generator,
			"Sensitive<T> must not be serialized. Hide the property from Jackson with @JsonIgnore or a mixin.",
		)
	}
}
