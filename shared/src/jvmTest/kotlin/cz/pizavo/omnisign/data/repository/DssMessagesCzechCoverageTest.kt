package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle

/**
 * Guards the bundled Czech DSS validation-message translation (`dss-messages_cs.properties`).
 *
 * DSS 6.3 ships only an English `dss-messages.properties`; our resource supplies the Czech variant
 * that [DssValidationRepository] selects through the validator's locale. This test asserts the Czech
 * bundle covers **every** key in DSS's base bundle — so no English text can leak into a Czech report —
 * and therefore fails if a future DSS upgrade introduces message keys we have not yet translated.
 */
class DssMessagesCzechCoverageTest : FunSpec({

	fun loadProperties(resource: String): Properties = Properties().apply {
		val stream = DssMessagesCzechCoverageTest::class.java.getResourceAsStream(resource)
			?: error("resource not found on the classpath: $resource")
		stream.reader(Charsets.UTF_8).use { load(it) }
	}

	val base = loadProperties("/dss-messages.properties")
	val czech = loadProperties("/dss-messages_cs.properties")

	test("the Czech bundle covers every DSS base message key") {
		val czechKeys = czech.stringPropertyNames()
		val untranslated = base.stringPropertyNames().filter { it !in czechKeys }.sorted()

		untranslated.shouldBeEmpty()
	}

	test("a Czech locale resolves DSS messages from the Czech bundle rather than English") {
		val key = "BBB_XCV_ISCR_ANS"
		val bundle = ResourceBundle.getBundle("dss-messages", Locale.forLanguageTag("cs"))

		bundle.getString(key) shouldBe czech.getProperty(key)
		bundle.getString(key) shouldNotBe base.getProperty(key)
	}
})
