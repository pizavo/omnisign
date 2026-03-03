package cz.pizavo.omnisign.data.serializer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat

/**
 * [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementation for YAML format.
 *
 * Uses Jackson's [YAMLMapper] with null values excluded from output to produce clean YAML.
 * Both `.yaml` and `.yml` file extensions map to this serializer via [ConfigFormat.YAML].
 */
class YamlConfigSerializer : JacksonConfigSerializer(
	mapper = YAMLMapper.builder()
		.serializationInclusion(JsonInclude.Include.NON_NULL)
		.build()
) {
	override val format: ConfigFormat = ConfigFormat.YAML
}

