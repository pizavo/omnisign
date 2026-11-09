package cz.pizavo.omnisign.data.serializer

import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementation for JSON format.
 *
 * Uses Jackson's [JsonMapper] with pretty-printing enabled to produce human-readable output
 * that matches the style of the internal [cz.pizavo.omnisign.data.repository.FileConfigRepository].
 */
class JsonConfigSerializer : JacksonConfigSerializer(
	mapperBuilder = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
) {
	override val format: ConfigFormat = ConfigFormat.JSON
}

