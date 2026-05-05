package cz.pizavo.omnisign.ui.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [SettingsCategory] enum, focusing on the Appearance group
 * added for the Linux native-title-bar toggle.
 */
class SettingsCategoryTest : FunSpec({

	test("Appearance is a top-level group") {
		SettingsCategory.Appearance.parent shouldBe null
		SettingsCategory.Appearance.isGroup shouldBe true
	}

	test("WindowTitleBar is a child of Appearance") {
		SettingsCategory.WindowTitleBar.parent shouldBe SettingsCategory.Appearance
	}

	test("Appearance children contains WindowTitleBar") {
		SettingsCategory.Appearance.children shouldContain SettingsCategory.WindowTitleBar
		SettingsCategory.Appearance.children shouldHaveSize 1
	}

	test("groups list includes Appearance") {
		SettingsCategory.groups shouldContain SettingsCategory.Appearance
	}

	test("WindowTitleBar is not a group") {
		SettingsCategory.WindowTitleBar.isGroup shouldBe false
		SettingsCategory.WindowTitleBar.children shouldHaveSize 0
	}

	test("Appearance label and description are non-blank") {
		SettingsCategory.Appearance.label.isNotBlank() shouldBe true
		SettingsCategory.Appearance.description.isNotBlank() shouldBe true
	}

	test("WindowTitleBar label and description are non-blank") {
		SettingsCategory.WindowTitleBar.label.isNotBlank() shouldBe true
		SettingsCategory.WindowTitleBar.description.isNotBlank() shouldBe true
	}
})

