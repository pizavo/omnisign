package cz.pizavo.omnisign.data.serializer

import tools.jackson.dataformat.yaml.YAMLFactory
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLReadFeature
import tools.jackson.dataformat.yaml.YAMLSchema

/**
 * Start a [YAMLMapper] builder that reads configuration files under the YAML 1.2 core schema.
 *
 * Left alone, Jackson parses with the JSON schema, where only a lowercase `null` means null. A
 * hand-written `password: ~` would then load without complaint as the one-character password `~`,
 * and `activeProfile: ~` as a profile of that name. The core schema, the one the YAML specification
 * recommends for general use, reads `~`, `null`, `Null`, `NULL` and an empty value as null.
 *
 * Integers are decimal unless prefixed, as the core schema defines them, so
 * [YAMLReadFeature.PARSE_OCTAL_NUMBERS] is off: it would take `010` for eight.
 *
 * @return A builder for the caller to finish with its own modules and features.
 */
fun configYamlMapperBuilder(): YAMLMapper.Builder =
	YAMLMapper.builder(
		YAMLFactory.builder()
			.yamlSchema(YAMLSchema.CORE)
			.disable(YAMLReadFeature.PARSE_OCTAL_NUMBERS)
			.build(),
	)
