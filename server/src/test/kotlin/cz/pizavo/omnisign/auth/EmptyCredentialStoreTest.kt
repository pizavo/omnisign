package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies that [EmptyCredentialStore] keeps nothing it is given and says so.
 */
class EmptyCredentialStoreTest : FunSpec({

	test("does not keep a password it is given, and reports that it did not") {
		val store = EmptyCredentialStore()

		val result = store.setPassword("omnisign-token", "token-1", "1234")

		result.isLeft() shouldBe true
		result.swap().getOrNull().shouldBeInstanceOf<ConfigurationError.SaveFailed>()
		store.getPassword("omnisign-token", "token-1").shouldBeNull()
	}

	test("reports that it does not persist") {
		EmptyCredentialStore().isPersistent.shouldBeFalse()
	}
})
