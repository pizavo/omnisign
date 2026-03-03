package cz.pizavo.omnisign.data.serializer

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat

/**
 * [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementation for JSON format.
 *
 * Uses Jackson's [JsonMapper] with pretty-printing enabled to produce human-readable output
 * that matches the style of the internal [cz.pizavo.omnisign.data.repository.FileConfigRepository].
 */
class JsonConfigSerializer : JacksonConfigSerializer(
	mapper = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.build()
) {
	override val format: ConfigFormat = ConfigFormat.JSON
}

