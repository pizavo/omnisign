package cz.pizavo.omnisign.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive

/**
 * Jackson deserializer that lifts a bare YAML/JSON string into a [Sensitive] wrapper of
 * type `Sensitive<String>`.
 *
 * The shared module already provides a kotlinx-serialization `SensitiveSerializer` that
 * unconditionally throws — that policy is right for serialization (a sensitive value must
 * never leave the process boundary unintentionally) but wrong for the server's
 * configuration-loading path, which DOES need to construct `Sensitive<String>` instances
 * from a YAML literal at startup. This deserializer fills that gap and is registered in
 * [ServerConfigLoader]'s `ObjectMapper`. No matching serializer is registered: the server
 * never writes `ServerConfig` out, and the absence of a Jackson serializer means an
 * accidental `mapper.writeValue(serverConfig)` would fail loudly rather than silently
 * emit the wrapped secret.
 *
 * The opacity guarantee from [Sensitive.toString] is unaffected — the deserializer only
 * decides how a string token becomes a wrapper instance; what `toString` returns is the
 * value class's own concern, and it always returns `***`.
 */
class SensitiveStringJacksonDeserializer : JsonDeserializer<Sensitive<String>>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Sensitive<String> =
        parser.valueAsString.sensitive()
}
