package cz.pizavo.omnisign.data.serializer

import com.fasterxml.jackson.annotation.JsonInclude
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementation for JSON format.
 *
 * Uses Jackson's [JsonMapper] with pretty-printing enabled to produce human-readable output
 * that matches the style of the internal [cz.pizavo.omnisign.data.repository.FileConfigRepository].
 * Null values are left out, as the YAML and XML serializers leave them out. An unset setting
 * then reads back as unset, and a build that knew the setting only as one that always held a
 * value reads its own default instead of refusing the export over a `null`.
 */
class JsonConfigSerializer : JacksonConfigSerializer(
	mapperBuilder = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
) {
	override val format: ConfigFormat = ConfigFormat.JSON
}

