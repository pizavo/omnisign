package cz.pizavo.omnisign.data.serializer

import com.fasterxml.jackson.annotation.JsonInclude
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.module.SimpleModule
import tools.jackson.dataformat.xml.XmlMapper

/**
 * [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementation for XML format.
 *
 * Uses Jackson's [XmlMapper] with pretty-printing enabled and null values excluded from output.
 * Excluding nulls prevents empty XML elements from causing deserialization failures when
 * the corresponding Kotlin field has a non-nullable primitive type with a default value.
 *
 * An empty set is written as an empty element, which reaches the reader as an empty string.
 * [XmlMapper] turns that into an empty collection, but left alone Jackson binds a [Set] of enum
 * constants to an `EnumSet`, whose deserializer skips that rule and refuses the string. Mapping [Set]
 * to [LinkedHashSet] keeps every set on the general collection path, so an exported
 * `<disabledHashAlgorithms/>` reads back.
 */
class XmlConfigSerializer : JacksonConfigSerializer(
	mapperBuilder = XmlMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
		.addModule(SimpleModule().addAbstractTypeMapping(Set::class.java, LinkedHashSet::class.java))
) {
	override val format: ConfigFormat = ConfigFormat.XML
}

