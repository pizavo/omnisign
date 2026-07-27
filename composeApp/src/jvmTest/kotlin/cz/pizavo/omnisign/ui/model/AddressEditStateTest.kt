package cz.pizavo.omnisign.ui.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies the builder's address form state knows when it is complete enough to compile, and maps
 * cleanly onto the domain address.
 *
 * The distinction under test is which parts ETSI TS 119612 actually requires: demanding a state or
 * postal code would block territories that use neither, while letting a blank street through would
 * produce a draft the compiler rejects with a message the user has to work backwards from.
 */
class AddressEditStateTest : FunSpec({

	val complete = AddressEditState(
		street = "Technicka 2",
		locality = "Praha",
		country = "CZ",
		electronicAddress = "mailto:tl@omnisign.test",
	)

	test("an empty form is not complete") {
		AddressEditState().isComplete() shouldBe false
	}

	test("street, town, country and contact URI together are enough") {
		complete.isComplete() shouldBe true
	}

	test("the optional parts are not required for completeness") {
		complete.copy(stateOrProvince = "", postalCode = "").isComplete() shouldBe true
	}

	test("each required part is genuinely required") {
		complete.copy(street = "").isComplete() shouldBe false
		complete.copy(locality = "").isComplete() shouldBe false
		complete.copy(country = "").isComplete() shouldBe false
		complete.copy(electronicAddress = "").isComplete() shouldBe false
	}

	test("a blank-but-not-empty part does not count as filled in") {
		complete.copy(street = "   ").isComplete() shouldBe false
	}

	test("maps onto the domain address, trimming as it goes") {
		val address = complete.copy(
			street = "  Technicka 2 ",
			stateOrProvince = " Praha ",
			postalCode = " 16000 ",
		).toAddress()

		address.streetAddress shouldBe "Technicka 2"
		address.locality shouldBe "Praha"
		address.countryName shouldBe "CZ"
		address.stateOrProvince shouldBe "Praha"
		address.postalCode shouldBe "16000"
		address.electronicAddress shouldBe "mailto:tl@omnisign.test"
	}

	test("drops the optional parts rather than carrying empty strings into the list") {
		val address = complete.copy(stateOrProvince = "   ", postalCode = "").toAddress()

		address.stateOrProvince shouldBe null
		address.postalCode shouldBe null
	}
})
