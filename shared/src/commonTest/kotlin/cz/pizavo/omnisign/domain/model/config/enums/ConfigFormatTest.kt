package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Verifies [ConfigFormat] extension properties and [ConfigFormat.fromExtension] resolution.
 */
class ConfigFormatTest : FunSpec({

	test("extension property returns correct values") {
		ConfigFormat.JSON.extension shouldBe "json"
		ConfigFormat.XML.extension shouldBe "xml"
		ConfigFormat.YAML.extension shouldBe "yaml"
	}

	test("fromExtension resolves json case-insensitively") {
		ConfigFormat.fromExtension("json") shouldBe ConfigFormat.JSON
		ConfigFormat.fromExtension("JSON") shouldBe ConfigFormat.JSON
		ConfigFormat.fromExtension("Json") shouldBe ConfigFormat.JSON
	}

	test("fromExtension resolves xml case-insensitively") {
		ConfigFormat.fromExtension("xml") shouldBe ConfigFormat.XML
		ConfigFormat.fromExtension("XML") shouldBe ConfigFormat.XML
	}

	test("fromExtension resolves yaml and yml aliases") {
		ConfigFormat.fromExtension("yaml") shouldBe ConfigFormat.YAML
		ConfigFormat.fromExtension("yml") shouldBe ConfigFormat.YAML
		ConfigFormat.fromExtension("YML") shouldBe ConfigFormat.YAML
		ConfigFormat.fromExtension("YAML") shouldBe ConfigFormat.YAML
	}

	test("fromExtension strips leading dot") {
		ConfigFormat.fromExtension(".json") shouldBe ConfigFormat.JSON
		ConfigFormat.fromExtension(".xml") shouldBe ConfigFormat.XML
		ConfigFormat.fromExtension(".yaml") shouldBe ConfigFormat.YAML
		ConfigFormat.fromExtension(".yml") shouldBe ConfigFormat.YAML
	}

	test("fromExtension returns null for unknown format") {
		ConfigFormat.fromExtension("toml").shouldBeNull()
		ConfigFormat.fromExtension("ini").shouldBeNull()
		ConfigFormat.fromExtension("csv").shouldBeNull()
		ConfigFormat.fromExtension("").shouldBeNull()
	}

	test("all entries have non-blank extension") {
		ConfigFormat.entries.forEach { format -> format.extension.shouldNotBeBlank() }
	}

	test("entries count is three") {
		ConfigFormat.entries.size shouldBe 3
	}
})

