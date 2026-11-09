package cz.pizavo.omnisign.config

import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.node.ObjectNode

/**
 * Jackson deserializer for [SsoProviderConfig] that dispatches on the `type` field.
 *
 * Supported type values:
 * - `oidc` → [OidcProviderConfig]
 *
 * The `type` discriminator is consumed by this deserializer and then stripped from the
 * node before the concrete subtype is bound, so that [ServerConfigLoader]'s strict
 * `FAIL_ON_UNKNOWN_PROPERTIES` setting does not flag the discriminator as an unknown
 * field on the chosen subtype.
 */
class SsoProviderConfigDeserializer : ValueDeserializer<SsoProviderConfig>() {

    /**
     * Deserialize a JSON/YAML node into the appropriate [SsoProviderConfig] subtype
     * based on the `type` discriminator field.
     *
     * @throws IllegalArgumentException when the `type` value is unknown or missing.
     */
    override fun deserialize(parser: JsonParser, context: DeserializationContext): SsoProviderConfig {
        val node: JsonNode = context.readTree(parser)
        val type = node.get("type")?.asString("")
            ?: throw IllegalArgumentException("SSO provider config is missing the required 'type' field")

        val withoutType: JsonNode = (node as? ObjectNode)?.also { it.remove("type") } ?: node

        return when (type.lowercase()) {
            "oidc" -> context.readTreeAsValue(withoutType, OidcProviderConfig::class.java)
            else -> throw IllegalArgumentException(
                "Unknown SSO provider type '$type'. Supported values: oidc",
            )
        }
    }
}

