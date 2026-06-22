package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle

/**
 * Guards the bundled Slovak DSS validation-message translation (`dss-messages_sk.properties`).
 *
 * Mirrors [DssMessagesCzechCoverageTest]: DSS 6.3 ships only an English `dss-messages.properties`;
 * our resource supplies the Slovak variant that [DssValidationRepository] selects through the
 * validator's locale. This test asserts the Slovak bundle covers **every** key in DSS's base bundle —
 * so no English text can leak into a Slovak report — and therefore fails if a future DSS upgrade
 * introduces message keys we have not yet translated.
 */
class DssMessagesSlovakCoverageTest : FunSpec({

	fun loadProperties(resource: String): Properties = Properties().apply {
		val stream = DssMessagesSlovakCoverageTest::class.java.getResourceAsStream(resource)
			?: error("resource not found on the classpath: $resource")
		stream.reader(Charsets.UTF_8).use { load(it) }
	}

	val base = loadProperties("/dss-messages.properties")
	val slovak = loadProperties("/dss-messages_sk.properties")

	test("the Slovak bundle covers every DSS base message key") {
		val slovakKeys = slovak.stringPropertyNames()
		val untranslated = base.stringPropertyNames().filter { it !in slovakKeys }.sorted()

		untranslated.shouldBeEmpty()
	}

	test("a Slovak locale resolves DSS messages from the Slovak bundle rather than English") {
		val key = "BBB_XCV_ISCR_ANS"
		val bundle = ResourceBundle.getBundle("dss-messages", Locale.forLanguageTag("sk"))

		bundle.getString(key) shouldBe slovak.getProperty(key)
		bundle.getString(key) shouldNotBe base.getProperty(key)
	}
})
