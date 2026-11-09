package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle

/**
 * Guards the bundled Czech DSS validation-message translation (`dss-messages_cs.properties`).
 *
 * DSS ships only an English `dss-messages.properties`; our resource supplies the Czech variant
 * that [DssValidationRepository] selects through the validator's locale. This test asserts the Czech
 * bundle covers **every** key in DSS's base bundle — so no English text can leak into a Czech report —
 * and therefore fails if a future DSS upgrade introduces message keys we have not yet translated.
 * It equally fails on keys DSS has since dropped and on translations whose `{n}` placeholders differ
 * from the English original, because DSS rewords messages between releases and sometimes changes the
 * arguments they take; a stale placeholder would otherwise surface in reports as a literal `{1}`.
 *
 * Finally, it fails on a message DSS cannot format as written. DSS passes every message through
 * `MessageFormat`, which reads a lone apostrophe as the start of a quoted section, so the apostrophe
 * vanishes and each placeholder after it is printed literally; an apostrophe meant as text has to be
 * doubled, as the English original does.
 */
class DssMessagesCzechCoverageTest : FunSpec({

	fun loadProperties(resource: String): Properties = Properties().apply {
		val stream = DssMessagesCzechCoverageTest::class.java.getResourceAsStream(resource)
			?: error("resource not found on the classpath: $resource")
		stream.reader(Charsets.UTF_8).use { load(it) }
	}

	fun placeholders(pattern: String): Set<String> =
		Regex("""\{\d+}""").findAll(pattern).map { it.value }.toSet()

	val base = loadProperties("/dss-messages.properties")
	val czech = loadProperties("/dss-messages_cs.properties")

	test("the Czech bundle covers every DSS base message key") {
		val czechKeys = czech.stringPropertyNames()
		val untranslated = base.stringPropertyNames().filter { it !in czechKeys }.sorted()

		untranslated.shouldBeEmpty()
	}

	test("the Czech bundle carries no keys that DSS no longer defines") {
		val baseKeys = base.stringPropertyNames()
		val obsolete = czech.stringPropertyNames().filter { it !in baseKeys }.sorted()

		obsolete.shouldBeEmpty()
	}

	test("every Czech message uses exactly the placeholders of its English original") {
		val mismatched = base.stringPropertyNames().filter { key ->
			czech.getProperty(key)?.let { placeholders(it) != placeholders(base.getProperty(key)) } ?: false
		}.sorted()

		mismatched.shouldBeEmpty()
	}

	test("every Czech message is a pattern DSS can format, with no apostrophe it would swallow") {
		val malformed = czech.stringPropertyNames().filter { key ->
			val pattern = czech.getProperty(key)
			'\'' in pattern.replace("''", "") || runCatching { MessageFormat(pattern, Locale.ROOT) }.isFailure
		}.sorted()

		malformed.shouldBeEmpty()
	}

	test("a Czech locale resolves DSS messages from the Czech bundle rather than English") {
		val key = "BBB_XCV_ISCR_ANS"
		val bundle = ResourceBundle.getBundle("dss-messages", Locale.forLanguageTag("cs"))

		bundle.getString(key) shouldBe czech.getProperty(key)
		bundle.getString(key) shouldNotBe base.getProperty(key)
	}
})
