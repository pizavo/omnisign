package cz.pizavo.omnisign.data.serializer

import arrow.core.Either
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies symmetric serialize→deserialize round-trips and invalid-input handling
 * for JSON, YAML, and XML serializers.
 */
class ConfigSerializerTest : FunSpec({
	
	val serializers = listOf(
		JsonConfigSerializer(),
		YamlConfigSerializer(),
		XmlConfigSerializer()
	)
	
	val sampleGlobal = GlobalConfig(
		defaultHashAlgorithm = HashAlgorithm.SHA512,
		defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LT
	)
	
	val sampleProfile = ProfileConfig(
		name = "test-profile",
		description = "A test profile",
		hashAlgorithm = HashAlgorithm.SHA384,
		signatureLevel = SignatureLevel.PADES_BASELINE_T
	)
	
	val sampleApp = AppConfig(
		global = sampleGlobal,
		profiles = mapOf("test-profile" to sampleProfile),
		activeProfile = "test-profile"
	)
	
	test("JSON serializer round-trips AppConfig") {
		val serializer = JsonConfigSerializer()
		val text = serializer.serializeApp(sampleApp).shouldBeRight()
		text.shouldNotBeBlank()
		
		val result = serializer.deserializeApp(text).shouldBeRight()
		result.global.defaultHashAlgorithm shouldBe sampleApp.global.defaultHashAlgorithm
		result.global.defaultSignatureLevel shouldBe sampleApp.global.defaultSignatureLevel
		result.activeProfile shouldBe sampleApp.activeProfile
		result.profiles.shouldHaveSize(1)
	}
	
	test("YAML serializer round-trips AppConfig") {
		val serializer = YamlConfigSerializer()
		val text = serializer.serializeApp(sampleApp).shouldBeRight()
		
		val result = serializer.deserializeApp(text).shouldBeRight()
		result.global.defaultHashAlgorithm shouldBe sampleApp.global.defaultHashAlgorithm
		result.activeProfile shouldBe sampleApp.activeProfile
	}
	
	test("XML serializer round-trips AppConfig") {
		val serializer = XmlConfigSerializer()
		val text = serializer.serializeApp(sampleApp).shouldBeRight()
		
		val result = serializer.deserializeApp(text).shouldBeRight()
		result.global.defaultHashAlgorithm shouldBe sampleApp.global.defaultHashAlgorithm
	}
	
	test("all serializers round-trip GlobalConfig") {
		for (serializer in serializers) {
			val text = serializer.serializeGlobal(sampleGlobal).shouldBeRight()
			val result = serializer.deserializeGlobal(text).shouldBeRight()
			result.defaultHashAlgorithm shouldBe sampleGlobal.defaultHashAlgorithm
			result.defaultSignatureLevel shouldBe sampleGlobal.defaultSignatureLevel
		}
	}
	
	test("all serializers round-trip ProfileConfig") {
		for (serializer in serializers) {
			val text = serializer.serializeProfile(sampleProfile).shouldBeRight()
			val result = serializer.deserializeProfile(text).shouldBeRight()
			result.name shouldBe sampleProfile.name
			result.hashAlgorithm shouldBe sampleProfile.hashAlgorithm
			result.description shouldBe sampleProfile.description
		}
	}
	
	test("JSON serializer returns error for invalid input") {
		val error = JsonConfigSerializer().deserializeApp("not valid json {{{")
			.shouldBeLeft()
		error.shouldBeInstanceOf<cz.pizavo.omnisign.domain.model.error.OperationError>()
		error.message.shouldNotBeNull()
	}
	
	test("YAML serializer returns error for invalid input") {
		val error = YamlConfigSerializer().deserializeApp("key: [unclosed bracket")
			.shouldBeLeft()
		error.shouldBeInstanceOf<cz.pizavo.omnisign.domain.model.error.OperationError>()
		error.message.shouldNotBeNull()
	}
	
	test("XML serializer returns error for invalid input") {
		val error = XmlConfigSerializer().deserializeApp("<unclosed")
			.shouldBeLeft()
		error.shouldBeInstanceOf<cz.pizavo.omnisign.domain.model.error.OperationError>()
		error.message.shouldNotBeNull()
	}
	
	test("JSON output is pretty-printed") {
		val text = (JsonConfigSerializer().serializeGlobal(sampleGlobal) as Either.Right).value
		text shouldContain "\n"
	}
	
	test("YAML output does not contain JSON braces") {
		val text = (YamlConfigSerializer().serializeGlobal(sampleGlobal) as Either.Right).value
		text.trimStart().shouldNotStartWith("{")
	}
	
	test("XML output contains XML declaration or root element") {
		val text = (XmlConfigSerializer().serializeGlobal(sampleGlobal) as Either.Right).value
		text shouldContain "<"
	}
})

