package cz.pizavo.omnisign.data.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [readableDistinguishedName] decodes RFC 2253/4514 value escaping for display while
 * leaving relative-distinguished-name separators and attribute types untouched.
 */
class ReadableDistinguishedNameTest : FunSpec({

    test("unescapes an in-value comma but keeps the RDN separators") {
        readableDistinguishedName("CN=Doe\\, John,O=Org,C=CZ") shouldBe "CN=Doe, John,O=Org,C=CZ"
    }

    test("decodes other escaped specials and an escaped backslash") {
        readableDistinguishedName("CN=A\\+B\\\\C") shouldBe "CN=A+B\\C"
    }

    test("decodes a run of hexadecimal byte escapes") {
        readableDistinguishedName("CN=\\23\\2A") shouldBe "CN=#*"
    }

    test("returns an unescaped distinguished name unchanged") {
        readableDistinguishedName("CN=Plain Name,O=Org,C=CZ") shouldBe "CN=Plain Name,O=Org,C=CZ"
    }
})
