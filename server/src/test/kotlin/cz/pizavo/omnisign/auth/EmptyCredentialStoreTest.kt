package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull

/**
 * Verifies that [EmptyCredentialStore] keeps nothing it is given and says so.
 */
class EmptyCredentialStoreTest : FunSpec({

	test("does not keep a password it is given") {
		val store = EmptyCredentialStore()

		store.setPassword("omnisign-token", "token-1", "1234")

		store.getPassword("omnisign-token", "token-1").shouldBeNull()
	}

	test("reports that it does not persist") {
		EmptyCredentialStore().isPersistent.shouldBeFalse()
	}
})
