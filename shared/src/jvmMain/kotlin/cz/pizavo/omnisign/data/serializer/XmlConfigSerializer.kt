package cz.pizavo.omnisign.data.serializer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat

/**
 * [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementation for XML format.
 *
 * Uses Jackson's [XmlMapper] with pretty-printing enabled and null values excluded from output.
 * Excluding nulls prevents empty XML elements from causing deserialization failures when
 * the corresponding Kotlin field has a non-nullable primitive type with a default value.
 */
class XmlConfigSerializer : JacksonConfigSerializer(
	mapper = XmlMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.serializationInclusion(JsonInclude.Include.NON_NULL)
		.build()
) {
	override val format: ConfigFormat = ConfigFormat.XML
}

