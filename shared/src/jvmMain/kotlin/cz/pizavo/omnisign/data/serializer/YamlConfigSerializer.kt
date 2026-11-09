package cz.pizavo.omnisign.data.serializer

import com.fasterxml.jackson.annotation.JsonInclude
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat

/**
 * [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementation for YAML format.
 *
 * Builds on [configYamlMapperBuilder], so a hand-written file is read under the YAML 1.2 core
 * schema, with null values excluded from output to produce clean YAML.
 * Both `.yaml` and `.yml` file extensions map to this serializer via [ConfigFormat.YAML].
 */
class YamlConfigSerializer : JacksonConfigSerializer(
	mapperBuilder = configYamlMapperBuilder()
		.changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
) {
	override val format: ConfigFormat = ConfigFormat.YAML
}

