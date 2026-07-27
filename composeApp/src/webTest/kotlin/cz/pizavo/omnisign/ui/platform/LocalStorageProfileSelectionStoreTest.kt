package cz.pizavo.omnisign.ui.platform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.localStorage

/**
 * Verifies [LocalStorageProfileSelectionStore] round-trips the active-profile selection through the
 * browser's real `localStorage`, and that clearing it removes the entry rather than storing an empty
 * sentinel — a stored blank would read back as a selection the UI could never highlight.
 */
class LocalStorageProfileSelectionStoreTest : FunSpec({

	val key = "omnisign.activeProfile"

	beforeTest { localStorage.removeItem(key) }
	afterTest { localStorage.removeItem(key) }

	test("reads back what it wrote") {
		val store = LocalStorageProfileSelectionStore()

		store.write("qualified")

		store.read() shouldBe "qualified"
	}

	test("reports no selection when nothing was ever stored") {
		LocalStorageProfileSelectionStore().read() shouldBe null
	}

	test("removes the entry instead of storing an empty value when cleared") {
		val store = LocalStorageProfileSelectionStore()
		store.write("qualified")

		store.write(null)

		store.read() shouldBe null
		localStorage.getItem(key) shouldBe null
	}

	test("treats a blank name as clearing the selection") {
		val store = LocalStorageProfileSelectionStore()
		store.write("qualified")

		store.write("   ")

		store.read() shouldBe null
		localStorage.getItem(key) shouldBe null
	}

	test("reads no selection when the stored value is blank") {
		localStorage.setItem(key, "  ")

		LocalStorageProfileSelectionStore().read() shouldBe null
	}

	test("namespaces its key so it cannot collide with other state on the origin") {
		LocalStorageProfileSelectionStore().write("qualified")

		localStorage.getItem(key) shouldBe "qualified"
	}
})
