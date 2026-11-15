package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies that [pkcs11TokenSerial] reads back the serial number [pkcs11TokenId] built a PKCS#11
 * token's id from, and finds none in the id of any other token.
 */
class Pkcs11TokenIdTest : FunSpec({

	/** A token of [type] with the id [id]. */
	fun token(id: String, type: TokenType) = TokenInfo(id = id, name = "Token", type = type)

	test("the serial a PKCS#11 token's id was built from is read back as it was given") {
		token(pkcs11TokenId("0123 abcd"), TokenType.PKCS11).pkcs11TokenSerial() shouldBe "0123 abcd"
	}

	test("a serial that itself begins with the id's prefix is read back whole") {
		token(pkcs11TokenId("pkcs11-7"), TokenType.PKCS11).pkcs11TokenSerial() shouldBe "pkcs11-7"
	}

	test("a PKCS#11 token whose id was not built from a serial has none") {
		token("keystore-card.p12", TokenType.PKCS11).pkcs11TokenSerial().shouldBeNull()
	}

	test("a token of another type has no serial, even when its id has that form") {
		token(pkcs11TokenId("SN1"), TokenType.FILE).pkcs11TokenSerial().shouldBeNull()
	}
})
