package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.i18n.I18nProvider
import eu.europa.esig.dss.i18n.MessageTag
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.Properties

/**
 * Tests for [dssMessageLocale], which picks the locale DSS renders its report messages in.
 *
 * DSS's own bundle lookup tries the JVM default locale before its English base bundle, so on a Czech
 * or Slovak machine it would answer a request in a language OmniSign does not translate DSS into
 * with the Czech or Slovak translation. Each test sets the default locale it runs under and restores
 * the previous one, in every category, when it ends.
 */
class DssMessageLocaleTest : FunSpec({

	fun messages(resource: String): Properties = Properties().apply {
		val stream = DssMessageLocaleTest::class.java.getResourceAsStream(resource)
			?: error("resource not found on the classpath: $resource")
		stream.reader(Charsets.UTF_8).use { load(it) }
	}

	val tag = MessageTag.BBB_XCV_CCCBB_SIG_ANS
	val english = messages("/dss-messages.properties").getProperty(tag.id)
	val czech = messages("/dss-messages_cs.properties").getProperty(tag.id)

	fun <T> withDefaultLocale(languageTag: String, block: () -> T): T {
		val default = Locale.getDefault()
		val display = Locale.getDefault(Locale.Category.DISPLAY)
		val format = Locale.getDefault(Locale.Category.FORMAT)
		Locale.setDefault(Locale.forLanguageTag(languageTag))
		return try {
			withClue("default locale $languageTag") { block() }
		} finally {
			Locale.setDefault(default)
			Locale.setDefault(Locale.Category.DISPLAY, display)
			Locale.setDefault(Locale.Category.FORMAT, format)
		}
	}

	fun message(locale: Locale): String = I18nProvider(locale).getMessage(tag)

	test("a language OmniSign translates DSS into resolves to that translation's locale, whatever the default") {
		for (default in listOf("en-US", "de-DE", "cs-CZ", "sk-SK")) {
			withDefaultLocale(default) {
				dssMessageLocale(Locale.forLanguageTag("cs")) shouldBe Locale.forLanguageTag("cs")
				dssMessageLocale(Locale.forLanguageTag("cs-CZ")) shouldBe Locale.forLanguageTag("cs")
				dssMessageLocale(Locale.forLanguageTag("sk")) shouldBe Locale.forLanguageTag("sk")
				dssMessageLocale(Locale.forLanguageTag("sk-SK")) shouldBe Locale.forLanguageTag("sk")
			}
		}
	}

	test("any other language resolves to the root locale on a Czech or Slovak machine, as the root locale does") {
		for (default in listOf("cs-CZ", "sk-SK")) {
			withDefaultLocale(default) {
				for (requested in listOf("en", "en-GB", "de", "pl")) {
					withClue(requested) { dssMessageLocale(Locale.forLanguageTag(requested)) shouldBe Locale.ROOT }
				}
				dssMessageLocale(Locale.ROOT) shouldBe Locale.ROOT
			}
		}
	}

	test("on a Czech machine DSS answers English in Czech, and in English once it is given the locale picked here") {
		withDefaultLocale("cs-CZ") {
			message(Locale.ENGLISH) shouldBe czech
			message(dssMessageLocale(Locale.ENGLISH)) shouldBe english
			message(dssMessageLocale(Locale.GERMAN)) shouldBe english
			message(dssMessageLocale(Locale.forLanguageTag("cs"))) shouldBe czech
		}
	}
})
