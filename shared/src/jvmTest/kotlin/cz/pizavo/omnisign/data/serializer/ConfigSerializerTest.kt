package cz.pizavo.omnisign.data.serializer

import arrow.core.Either
import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.SchedulerConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.value.sensitive
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import tools.jackson.databind.DatabindException
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

/**
 * Verifies symmetric serialize→deserialize round-trips and invalid-input handling
 * for JSON, YAML, and XML serializers, and how each format reads a hand-written file: what YAML takes
 * for null, a number, or a boolean, how XML writes an empty set, and what every format refuses.
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

	val populatedApp = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA3_512,
			defaultEncryptionAlgorithm = EncryptionAlgorithm.RSA_SSA_PSS,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA512, HashAlgorithm.SHA256),
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
			timestampServer = TimestampServerConfig(
				url = "https://tsa.example.cz/tsr?x=1&y=2",
				username = "uživatel",
				timeout = 12345,
			),
			validation = ValidationConfig(
				customTrustedLists = listOf(CustomTrustedListConfig("TL č. 1", "https://tl.example.cz/list.xml")),
				algorithmConstraints = AlgorithmConstraintsConfig(
					expirationDateOverrides = mapOf("SHA1" to "2020-01-01"),
				),
			),
			customPkcs11Libraries = listOf(CustomPkcs11Library("Karta", "C:\\Program Files\\x.dll", true)),
		),
		profiles = mapOf(
			"archival" to ProfileConfig(name = "archival", disabledHashAlgorithms = setOf(HashAlgorithm.WHIRLPOOL)),
			"tilde" to ProfileConfig(name = "tilde", description = "~"),
			"word" to ProfileConfig(name = "word", description = "no"),
			"nothing" to ProfileConfig(name = "nothing", description = "null"),
		),
		activeProfile = "archival",
		renewalJobs = mapOf("job" to RenewalJob(name = "job", globs = listOf("/srv/*.pdf"))),
		schedulerConfig = SchedulerConfig(runAtHour = 3, stalenessNotificationEnabled = false),
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

	test("all serializers export a config holding a TSA password without the password") {
		val withPassword = sampleApp.copy(
			global = sampleGlobal.copy(
				timestampServer = TimestampServerConfig(url = "https://tsa.example", runtimePassword = "hunter2".sensitive()),
			),
		)
		for (serializer in serializers) {
			serializer.serializeApp(withPassword).shouldBeRight() shouldNotContain "hunter2"
		}
	}

	test("all serializers refuse to write a Sensitive their mixin does not hide") {
		for (serializer in serializers) {
			val ex = shouldThrow<DatabindException> {
				serializer.mapper.writeValueAsString(mapOf("secret" to "hunter2".sensitive()))
			}
			ex.message.shouldNotBeNull() shouldContain "Sensitive<T> must not be serialized"
			ex.message.shouldNotBeNull() shouldNotContain "hunter2"
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

	test("every format round-trips a default AppConfig, whose sets are all empty") {
		for (serializer in serializers) {
			val text = serializer.serializeApp(AppConfig()).shouldBeRight()
			serializer.deserializeApp(text).shouldBeRight() shouldBe AppConfig()
		}
	}

	test("every format round-trips a populated AppConfig unchanged") {
		for (serializer in serializers) {
			val text = serializer.serializeApp(populatedApp).shouldBeRight()
			serializer.deserializeApp(text).shouldBeRight() shouldBe populatedApp
		}
	}

	test("XML reads a default AppConfig as exported before the move to Jackson 3") {
		val exported = """
			<AppConfig>
			  <global>
			    <defaultHashAlgorithm>SHA256</defaultHashAlgorithm>
			    <defaultSignatureLevel>PADES_BASELINE_B</defaultSignatureLevel>
			    <disabledHashAlgorithms/>
			    <disabledEncryptionAlgorithms/>
			    <ocsp>
			      <timeout>30000</timeout>
			    </ocsp>
			    <crl>
			      <timeout>30000</timeout>
			    </crl>
			    <validation>
			      <policyType>DEFAULT_ETSI</policyType>
			      <checkRevocation>true</checkRevocation>
			      <useEuLotl>true</useEuLotl>
			      <customTrustedLists/>
			      <trustedCertificates/>
			      <algorithmConstraints>
			        <expirationDateOverrides/>
			      </algorithmConstraints>
			    </validation>
			    <customPkcs11Libraries/>
			    <pkcs11ProbeTimeoutSeconds>30</pkcs11ProbeTimeoutSeconds>
			    <trustedListRefreshIntervalHours>4</trustedListRefreshIntervalHours>
			  </global>
			  <profiles/>
			  <tlDrafts/>
			  <renewalJobs/>
			  <schedulerConfig>
			    <runAtHour>2</runAtHour>
			    <runAtMinute>0</runAtMinute>
			    <stalenessNotificationEnabled>true</stalenessNotificationEnabled>
			    <stalenessThresholdDays>14</stalenessThresholdDays>
			  </schedulerConfig>
			</AppConfig>
		""".trimIndent()

		XmlConfigSerializer().deserializeApp(exported).shouldBeRight() shouldBe AppConfig()
	}

	test("XML reads an empty set element as an empty set, however it is written") {
		val elements = listOf(
			"<disabledHashAlgorithms/>",
			"<disabledHashAlgorithms></disabledHashAlgorithms>",
			"<disabledHashAlgorithms>\n  </disabledHashAlgorithms>",
		)
		for (element in elements) {
			val global = XmlConfigSerializer().deserializeGlobal("<GlobalConfig>$element</GlobalConfig>").shouldBeRight()
			global.disabledHashAlgorithms.shouldBeEmpty()
		}
	}

	test("XML reads repeated set items under their wrapper") {
		val item = { name: String -> "<disabledHashAlgorithms>$name</disabledHashAlgorithms>" }
		val document = "<GlobalConfig><disabledHashAlgorithms>${item("SHA512")}${item("SHA256")}</disabledHashAlgorithms></GlobalConfig>"

		val global = XmlConfigSerializer().deserializeGlobal(document).shouldBeRight()

		global.disabledHashAlgorithms shouldContainExactlyInAnyOrder setOf(HashAlgorithm.SHA512, HashAlgorithm.SHA256)
	}

	test("XML refuses an external entity instead of resolving it") {
		val secret = createTempFile("omnisign-xxe-", ".txt")
		secret.toFile().deleteOnExit()
		secret.writeText("TOP-SECRET")
		val document = """
			<!DOCTYPE AppConfig [<!ENTITY x SYSTEM "${secret.toUri()}">]>
			<AppConfig><activeProfile>&x;</activeProfile></AppConfig>
		""".trimIndent()

		val error = XmlConfigSerializer().deserializeApp(document).shouldBeLeft()

		error.details.shouldNotBeNull() shouldNotContain "TOP-SECRET"
	}

	test("YAML reads every spelling of null as null") {
		for (value in listOf("~", "null", "Null", "NULL", "")) {
			YamlConfigSerializer().deserializeApp("activeProfile: $value").shouldBeRight().activeProfile.shouldBeNull()
		}
	}

	test("YAML keeps a quoted tilde as text") {
		YamlConfigSerializer().deserializeApp("activeProfile: \"~\"").shouldBeRight().activeProfile shouldBe "~"
	}

	test("YAML reads an integer with leading zeros as decimal") {
		val hours = mapOf("07" to 7, "09" to 9, "010" to 10)
		for ((written, hour) in hours) {
			val config = YamlConfigSerializer().deserializeApp("schedulerConfig:\n  runAtHour: $written").shouldBeRight()
			config.schedulerConfig.runAtHour shouldBe hour
		}
	}

	test("YAML refuses the YAML 1.1 words for a boolean field") {
		for (word in listOf("yes", "no", "on", "off")) {
			YamlConfigSerializer().deserializeApp("schedulerConfig:\n  stalenessNotificationEnabled: $word").shouldBeLeft()
		}
	}

	test("YAML keeps the YAML 1.1 words as text in a string field") {
		for (word in listOf("yes", "no", "on", "off")) {
			YamlConfigSerializer().deserializeApp("activeProfile: $word").shouldBeRight().activeProfile shouldBe word
		}
	}

	test("every format refuses a null where a number is required") {
		JsonConfigSerializer().deserializeApp("""{"schedulerConfig":{"runAtHour":null}}""").shouldBeLeft()
		YamlConfigSerializer().deserializeApp("schedulerConfig:\n  runAtHour: ~").shouldBeLeft()
		XmlConfigSerializer().deserializeApp("<AppConfig><schedulerConfig><runAtHour/></schedulerConfig></AppConfig>").shouldBeLeft()
	}

	test("every format refuses a null inside a set") {
		JsonConfigSerializer().deserializeGlobal("""{"disabledHashAlgorithms":["SHA256",null]}""").shouldBeLeft()
		YamlConfigSerializer().deserializeGlobal("disabledHashAlgorithms: [SHA256, ~]").shouldBeLeft()
		XmlConfigSerializer().deserializeGlobal(
			"<GlobalConfig><disabledHashAlgorithms><disabledHashAlgorithms/></disabledHashAlgorithms></GlobalConfig>"
		).shouldBeLeft()
	}

	test("every format refuses content after the document instead of ignoring it") {
		JsonConfigSerializer().deserializeApp("{}{}").shouldBeLeft()
		YamlConfigSerializer().deserializeApp("activeProfile: a\n---\nactiveProfile: b").shouldBeLeft()
		XmlConfigSerializer().deserializeApp("<AppConfig/><AppConfig/>").shouldBeLeft()
	}

	test("every format refuses a document that holds nothing but null") {
		val documents = listOf(
			JsonConfigSerializer() to "null",
			YamlConfigSerializer() to "~",
			YamlConfigSerializer() to "null",
			YamlConfigSerializer() to "---",
			XmlConfigSerializer() to """<AppConfig xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:nil="true"/>""",
		)
		for ((serializer, document) in documents) {
			val results = listOf(
				serializer.deserializeApp(document),
				serializer.deserializeGlobal(document),
				serializer.deserializeProfile(document),
			)
			for (result in results) {
				val error = result.shouldBeLeft().shouldBeInstanceOf<ConfigurationError.LoadFailed>()
				error.message shouldBe "Failed to deserialize configuration from ${serializer.format.name}"
				error.details shouldBe "The document holds no configuration, only null"
			}
		}
	}
})

