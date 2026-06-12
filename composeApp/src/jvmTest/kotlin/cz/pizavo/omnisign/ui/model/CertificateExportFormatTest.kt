package cz.pizavo.omnisign.ui.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.util.Base64

/**
 * Verifies [CertificateExportFormat.encode] passes DER through unchanged for the binary formats and
 * wraps it in round-trippable PEM certificate armor for the text formats.
 */
class CertificateExportFormatTest : FunSpec({

    val der = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x2A, 0x13, 0x01, 0x41)

    test("binary formats write the raw DER bytes") {
        CertificateExportFormat.Der.encode(der) shouldBe der
        CertificateExportFormat.Cer.encode(der) shouldBe der
    }

    test("text formats wrap the DER in round-trippable PEM armor") {
        val pem = CertificateExportFormat.Pem.encode(der).decodeToString()
        pem shouldStartWith "-----BEGIN CERTIFICATE-----"
        pem.trimEnd() shouldEndWith "-----END CERTIFICATE-----"
        val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        Base64.getDecoder().decode(body) shouldBe der
    }
})
