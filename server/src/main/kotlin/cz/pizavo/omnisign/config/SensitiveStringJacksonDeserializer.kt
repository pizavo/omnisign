package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

/**
 * Jackson deserializer that lifts a bare YAML/JSON string into a [Sensitive] wrapper of
 * type `Sensitive<String>`.
 *
 * The shared module already provides a kotlinx-serialization `SensitiveSerializer` that
 * unconditionally throws — that policy is right for serialization (a sensitive value must
 * never leave the process boundary unintentionally) but wrong for the server's
 * configuration-loading path, which DOES need to construct `Sensitive<String>` instances
 * from a YAML literal at startup. This deserializer fills that gap and is registered in
 * the mappers of [ServerConfigLoader] and [SigningConfigLoader]. The server never writes
 * its configuration out, and the write direction is closed anyway: the same mappers
 * register [SensitiveJacksonSerializer][cz.pizavo.omnisign.data.serializer.SensitiveJacksonSerializer],
 * which throws, so an accidental `mapper.writeValue(serverConfig)` fails rather than
 * emitting the wrapped secret.
 *
 * The opacity guarantee from [Sensitive.toString] is unaffected — the deserializer only
 * decides how a string token becomes a wrapper instance; what `toString` returns is the
 * wrapper's own concern, and it always returns `***`.
 */
class SensitiveStringJacksonDeserializer : ValueDeserializer<Sensitive<String>>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Sensitive<String> =
        parser.valueAsString.sensitive()
}
