package cz.pizavo.omnisign.data.serializer

import arrow.core.Either
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import kotlin.test.*

/**
 * Unit tests for the three [cz.pizavo.omnisign.domain.port.ConfigSerializer] implementations.
 *
 * Each test verifies a symmetric serialize → deserialize round-trip preserves all fields,
 * and that deserializing invalid input produces an [Either.Left] containing a descriptive error.
 */
class ConfigSerializerTest {
	
	private val serializers = listOf(
		JsonConfigSerializer(),
		YamlConfigSerializer(),
		XmlConfigSerializer()
	)
	
	private val sampleGlobal = GlobalConfig(
		defaultHashAlgorithm = HashAlgorithm.SHA512,
		defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LT
	)
	
	private val sampleProfile = ProfileConfig(
		name = "test-profile",
		description = "A test profile",
		hashAlgorithm = HashAlgorithm.SHA384,
		signatureLevel = SignatureLevel.PADES_BASELINE_T
	)
	
	private val sampleApp = AppConfig(
		global = sampleGlobal,
		profiles = mapOf("test-profile" to sampleProfile),
		activeProfile = "test-profile"
	)
	
	@Test
	fun `JSON serializer round-trips AppConfig`() {
		val serializer = JsonConfigSerializer()
		val text = serializer.serializeApp(sampleApp)
		assertIs<Either.Right<String>>(text)
		assertTrue(text.value.isNotBlank())
		
		val result = serializer.deserializeApp(text.value)
		assertIs<Either.Right<AppConfig>>(result)
		assertEquals(sampleApp.global.defaultHashAlgorithm, result.value.global.defaultHashAlgorithm)
		assertEquals(sampleApp.global.defaultSignatureLevel, result.value.global.defaultSignatureLevel)
		assertEquals(sampleApp.activeProfile, result.value.activeProfile)
		assertEquals(1, result.value.profiles.size)
	}
	
	@Test
	fun `YAML serializer round-trips AppConfig`() {
		val serializer = YamlConfigSerializer()
		val text = serializer.serializeApp(sampleApp)
		assertIs<Either.Right<String>>(text)
		
		val result = serializer.deserializeApp(text.value)
		assertIs<Either.Right<AppConfig>>(result)
		assertEquals(sampleApp.global.defaultHashAlgorithm, result.value.global.defaultHashAlgorithm)
		assertEquals(sampleApp.activeProfile, result.value.activeProfile)
	}
	
	@Test
	fun `XML serializer round-trips AppConfig`() {
		val serializer = XmlConfigSerializer()
		val text = serializer.serializeApp(sampleApp)
		assertIs<Either.Right<String>>(text)
		
		val result = serializer.deserializeApp(text.value)
		assertIs<Either.Right<AppConfig>>(result)
		assertEquals(sampleApp.global.defaultHashAlgorithm, result.value.global.defaultHashAlgorithm)
	}
	
	@Test
	fun `all serializers round-trip GlobalConfig`() {
		for (serializer in serializers) {
			val text = serializer.serializeGlobal(sampleGlobal)
			assertIs<Either.Right<String>>(text, "${serializer.format}: serialize failed")
			
			val result = serializer.deserializeGlobal(text.value)
			assertIs<Either.Right<GlobalConfig>>(result, "${serializer.format}: deserialize failed")
			assertEquals(
				sampleGlobal.defaultHashAlgorithm,
				result.value.defaultHashAlgorithm,
				"${serializer.format}: hash algorithm mismatch"
			)
			assertEquals(
				sampleGlobal.defaultSignatureLevel,
				result.value.defaultSignatureLevel,
				"${serializer.format}: signature level mismatch"
			)
		}
	}
	
	@Test
	fun `all serializers round-trip ProfileConfig`() {
		for (serializer in serializers) {
			val text = serializer.serializeProfile(sampleProfile)
			assertIs<Either.Right<String>>(text, "${serializer.format}: serialize failed")
			
			val result = serializer.deserializeProfile(text.value)
			assertIs<Either.Right<ProfileConfig>>(result, "${serializer.format}: deserialize failed")
			assertEquals(
				sampleProfile.name,
				result.value.name,
				"${serializer.format}: profile name mismatch"
			)
			assertEquals(
				sampleProfile.hashAlgorithm,
				result.value.hashAlgorithm,
				"${serializer.format}: hash algorithm mismatch"
			)
			assertEquals(
				sampleProfile.description,
				result.value.description,
				"${serializer.format}: description mismatch"
			)
		}
	}
	
	@Test
	fun `JSON serializer returns error for invalid input`() {
		val serializer = JsonConfigSerializer()
		val result = serializer.deserializeApp("not valid json {{{")
		assertIs<Either.Left<*>>(result)
		val error = result.value
		assertIs<cz.pizavo.omnisign.domain.model.error.OperationError>(error)
		assertNotNull(error.message)
	}
	
	@Test
	fun `YAML serializer returns error for invalid input`() {
		val serializer = YamlConfigSerializer()
		val result = serializer.deserializeApp("key: [unclosed bracket")
		assertIs<Either.Left<*>>(result)
		val error = result.value
		assertIs<cz.pizavo.omnisign.domain.model.error.OperationError>(error)
		assertNotNull(error.message)
	}
	
	@Test
	fun `XML serializer returns error for invalid input`() {
		val serializer = XmlConfigSerializer()
		val result = serializer.deserializeApp("<unclosed")
		assertIs<Either.Left<*>>(result)
		val error = result.value
		assertIs<cz.pizavo.omnisign.domain.model.error.OperationError>(error)
		assertNotNull(error.message)
	}
	
	@Test
	fun `JSON output is pretty-printed`() {
		val serializer = JsonConfigSerializer()
		val text = (serializer.serializeGlobal(sampleGlobal) as Either.Right).value
		assertTrue(text.contains('\n'), "Expected pretty-printed JSON with newlines")
	}
	
	@Test
	fun `YAML output does not contain JSON braces`() {
		val serializer = YamlConfigSerializer()
		val text = (serializer.serializeGlobal(sampleGlobal) as Either.Right).value
		assertTrue(!text.trimStart().startsWith("{"), "Expected YAML output, not JSON object")
	}
	
	@Test
	fun `XML output contains XML declaration or root element`() {
		val serializer = XmlConfigSerializer()
		val text = (serializer.serializeGlobal(sampleGlobal) as Either.Right).value
		assertTrue(
			text.contains('<'),
			"Expected XML output to contain angle brackets"
		)
	}
}




