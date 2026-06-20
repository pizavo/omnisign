package cz.pizavo.omnisign.domain.model.value

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Verifies that [FormatPreferences] (and the [DateFormat] enum it carries) round-trips through JSON,
 * which is the contract the cross-surface `preferences/format.json` store and the web `localStorage`
 * blob rely on.
 */
class FormatPreferencesSerializationTest : FunSpec({

	val json = Json

	test("the default FormatPreferences round-trips through JSON") {
		val prefs = FormatPreferences()
		val encoded = json.encodeToString(FormatPreferences.serializer(), prefs)

		json.decodeFromString(FormatPreferences.serializer(), encoded) shouldBe prefs
		prefs.dateFormat shouldBe DateFormat.SYSTEM
	}

	test("every DateFormat round-trips by entry name") {
		DateFormat.entries.forEach { format ->
			val prefs = FormatPreferences(format)
			val encoded = json.encodeToString(FormatPreferences.serializer(), prefs)

			json.decodeFromString(FormatPreferences.serializer(), encoded) shouldBe prefs
		}
	}

	test("a non-default date format is serialized as its entry name") {
		val encoded = json.encodeToString(FormatPreferences.serializer(), FormatPreferences(DateFormat.DMY_DOT))

		encoded shouldBe """{"dateFormat":"DMY_DOT"}"""
	}
})
