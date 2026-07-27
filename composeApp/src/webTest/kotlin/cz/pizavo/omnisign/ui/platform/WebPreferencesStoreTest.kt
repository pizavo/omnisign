package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.FormatPreferences
import cz.pizavo.omnisign.ui.model.UiPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.localStorage

/**
 * Verifies the Wasm/JS preference store round-trips through the browser's real `localStorage`, keeps
 * the two preference documents apart, and honours the `@Transient` marker on
 * [UiPreferences.format].
 *
 * The fallback cases matter most. Each accessor swallows every exception and answers with a default,
 * so a value corrupted by a half-finished write — or written by an older build with an incompatible
 * shape — must degrade to "no preference" rather than take the app down on boot. A swallow that
 * broad can only be trusted if it is exercised.
 */
class WebPreferencesStoreTest : FunSpec({

	val uiKey = "omnisign.preferences.ui"
	val formatKey = "omnisign.preferences.format"

	beforeTest {
		localStorage.removeItem(uiKey)
		localStorage.removeItem(formatKey)
	}

	afterTest {
		localStorage.removeItem(uiKey)
		localStorage.removeItem(formatKey)
	}

	test("returns defaults when no chrome preferences are stored") {
		readUiPreferences() shouldBe UiPreferences()
	}

	test("round-trips the chrome preferences") {
		writeUiPreferences(UiPreferences(isDark = true, useNativeTitleBar = false, languageTag = "cs"))

		val stored = readUiPreferences()

		stored.isDark shouldBe true
		stored.useNativeTitleBar shouldBe false
		stored.languageTag shouldBe "cs"
	}

	test("keeps the transient format field out of the chrome document") {
		writeUiPreferences(
			UiPreferences(format = FormatPreferences(DateFormat.DMY_DOT), languageTag = "cs"),
		)

		localStorage.getItem(uiKey)?.contains("DMY_DOT") shouldBe false
		readUiPreferences().format shouldBe FormatPreferences()
	}

	test("falls back to defaults rather than throwing on a corrupt chrome document") {
		localStorage.setItem(uiKey, "{ this is not json")

		readUiPreferences() shouldBe UiPreferences()
	}

	test("reads no date format when none is stored") {
		readFormatPreference() shouldBe null
	}

	test("round-trips the date format in its own document") {
		writeFormatPreference(DateFormat.DMY_DOT)

		readFormatPreference() shouldBe DateFormat.DMY_DOT
		localStorage.getItem(uiKey) shouldBe null
	}

	test("reads no date format rather than throwing on a corrupt format document") {
		localStorage.setItem(formatKey, "{ this is not json")

		readFormatPreference() shouldBe null
	}

	test("keeps an unknown enum value from taking down the boot") {
		localStorage.setItem(formatKey, """{"dateFormat":"FORMAT_FROM_A_NEWER_BUILD"}""")

		readFormatPreference() shouldBe null
	}
})
