package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle

/**
 * Guards the bundled Slovak renewal-notification translation (`renewal-notifications_sk.properties`)
 * against drift from its English base (`renewal-notifications.properties`).
 *
 * Mirrors [RenewalNotificationsCzechCoverageTest]: asserts the Slovak bundle translates **every** key
 * in the base bundle — so no English text can leak into a Slovak notification — and that a Slovak
 * locale resolves to the Slovak variant rather than the English base.
 */
class RenewalNotificationsSlovakCoverageTest : FunSpec({

	fun loadProperties(resource: String): Properties = Properties().apply {
		val stream = RenewalNotificationsSlovakCoverageTest::class.java.getResourceAsStream(resource)
			?: error("resource not found on the classpath: $resource")
		stream.reader(Charsets.UTF_8).use { load(it) }
	}

	val base = loadProperties("/renewal-notifications.properties")
	val slovak = loadProperties("/renewal-notifications_sk.properties")

	test("the Slovak bundle translates every base notification key") {
		val slovakKeys = slovak.stringPropertyNames()
		val untranslated = base.stringPropertyNames().filter { it !in slovakKeys }.sorted()

		untranslated.shouldBeEmpty()
	}

	test("a Slovak locale resolves notifications from the Slovak bundle rather than English") {
		val key = "complete.title"
		val bundle = ResourceBundle.getBundle("renewal-notifications", Locale.forLanguageTag("sk"))

		bundle.getString(key) shouldBe slovak.getProperty(key)
		bundle.getString(key) shouldNotBe base.getProperty(key)
	}
})
