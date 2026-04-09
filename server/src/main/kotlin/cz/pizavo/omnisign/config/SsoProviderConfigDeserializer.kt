package cz.pizavo.omnisign.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Jackson deserializer for [SsoProviderConfig] that dispatches on the `type` field.
 *
 * Supported type values:
 * - `oidc` → [OidcProviderConfig]
 * - `header-injection` → [HeaderInjectionProviderConfig]
 */
class SsoProviderConfigDeserializer : JsonDeserializer<SsoProviderConfig>() {

    /**
     * Deserialize a JSON/YAML node into the appropriate [SsoProviderConfig] subtype
     * based on the `type` discriminator field.
     *
     * @throws IllegalArgumentException when the `type` value is unknown or missing.
     */
    override fun deserialize(parser: JsonParser, context: DeserializationContext): SsoProviderConfig {
        val mapper = parser.codec as ObjectMapper
        val node: JsonNode = mapper.readTree(parser)
        val type = node.get("type")?.asText()
            ?: throw IllegalArgumentException("SSO provider config is missing the required 'type' field")

        return when (type.lowercase()) {
            "oidc" -> mapper.treeToValue(node, OidcProviderConfig::class.java)
            "header-injection" -> mapper.treeToValue(node, HeaderInjectionProviderConfig::class.java)
            else -> throw IllegalArgumentException(
                "Unknown SSO provider type '$type'. Supported values: oidc, header-injection",
            )
        }
    }
}

