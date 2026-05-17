package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [trimPkcs11Field]'s decoding of fixed-length `CK_TOKEN_INFO` character fields:
 * the PKCS#11-spec space padding (`0x20`) and the SafeNet null-byte (`0x00`) padding
 * convention are both stripped, including the mixed and all-padding cases.
 */
class Pkcs11ProbeLibTest : FunSpec({

	test("trimPkcs11Field strips null-byte padding used by SafeNet middleware") {
		val padded = "VP-SafeNet".toByteArray(Charsets.UTF_8) + ByteArray(22)
		padded.trimPkcs11Field() shouldBe "VP-SafeNet"
	}

	test("trimPkcs11Field strips space padding mandated by PKCS11 spec") {
		val padded = ("ABC123" + " ".repeat(10)).toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe "ABC123"
	}

	test("trimPkcs11Field handles mixed null-byte and space padding") {
		val padded = "SN-42".toByteArray(Charsets.UTF_8) + ByteArray(2) + " ".repeat(3).toByteArray(Charsets.UTF_8)
		padded.trimPkcs11Field() shouldBe "SN-42"
	}

	test("trimPkcs11Field returns empty string for all-null-byte field") {
		val padded = ByteArray(8)
		padded.trimPkcs11Field() shouldBe ""
	}
})
