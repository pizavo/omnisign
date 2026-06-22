package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle

/**
 * Guards the bundled Czech renewal-notification translation (`renewal-notifications_cs.properties`)
 * against drift from its English base (`renewal-notifications.properties`).
 *
 * Asserts the Czech bundle translates **every** key in the base bundle — so no English text can leak
 * into a Czech notification — and that a Czech locale actually resolves to the Czech variant rather
 * than the English base. Adding a notification key without its Czech translation fails this test.
 */
class RenewalNotificationsCzechCoverageTest : FunSpec({

	fun loadProperties(resource: String): Properties = Properties().apply {
		val stream = RenewalNotificationsCzechCoverageTest::class.java.getResourceAsStream(resource)
			?: error("resource not found on the classpath: $resource")
		stream.reader(Charsets.UTF_8).use { load(it) }
	}

	val base = loadProperties("/renewal-notifications.properties")
	val czech = loadProperties("/renewal-notifications_cs.properties")

	test("the Czech bundle translates every base notification key") {
		val czechKeys = czech.stringPropertyNames()
		val untranslated = base.stringPropertyNames().filter { it !in czechKeys }.sorted()

		untranslated.shouldBeEmpty()
	}

	test("a Czech locale resolves notifications from the Czech bundle rather than English") {
		val key = "complete.title"
		val bundle = ResourceBundle.getBundle("renewal-notifications", Locale.forLanguageTag("cs"))

		bundle.getString(key) shouldBe czech.getProperty(key)
		bundle.getString(key) shouldNotBe base.getProperty(key)
	}
})
