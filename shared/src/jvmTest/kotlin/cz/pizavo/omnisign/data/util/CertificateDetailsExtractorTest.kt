package cz.pizavo.omnisign.data.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Base64

/**
 * Verifies [extractCertificateDetails] against a self-signed certificate carrying
 * `CN=Test Signer, OU=Unit, O=Org, C=CZ`, a Subject Alternative Name (email + DNS), a critical
 * Key Usage, and Basic Constraints — covering the DN enumeration and the decoded extensions — and
 * against a second certificate whose common name contains a comma, covering RFC 4514 unescaping.
 */
class CertificateDetailsExtractorTest : FunSpec({

    val der = Base64.getDecoder().decode(
        "MIIDajCCAlKgAwIBAgIHec6s6JWe4TANBgkqhkiG9w0BAQwFADBAMQswCQYDVQQGEwJDWjEMMAoGA1UEChMDT3Jn" +
            "MQ0wCwYDVQQLEwRVbml0MRQwEgYDVQQDEwtUZXN0IFNpZ25lcjAeFw0yNjA2MDkyMzE4NTRaFw0yNzA2MDky" +
            "MzE4NTRaMEAxCzAJBgNVBAYTAkNaMQwwCgYDVQQKEwNPcmcxDTALBgNVBAsTBFVuaXQxFDASBgNVBAMTC1Rl" +
            "c3QgU2lnbmVyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAiwhwWvXV8cYmKFUzOJeeJZwKhwCN" +
            "nGPjxVyWUj0n9rlyB5frFrQhgOoZZYSZIFF756IT+MYOk5xqHoQ1AAz4M1tmmClFVMKttNeQWxMPAaVmB0Ta" +
            "xuDNRmyy+U9DbR0fofMmeZj0Z+X/VXvVrUpCSHHPAd+84u89W9Q+Y9B6f1X3UWsBNyWggu/YDgJ41ll1gwPE" +
            "b5QCjAD/hBt9uz5LmeiRkUwZJbJ59sNX7UNlAcPKRSdq5XAOSWPFWg+a4vBkchpocoCkYzM/IE/yl/PDw9Sk" +
            "LX+sFhoP7if1PWs6WeoGRCbtptyynBmEoklgy4sD0mF9j5LGhA0/qfaoz1YUUwIDAQABo2kwZzAdBgNVHQ4E" +
            "FgQULru7/YXLTMgpvvUvtqHkUiszvyAwDgYDVR0PAQH/BAQDAgbAMCgGA1UdEQQhMB+BEHRlc3RAZXhhbXBs" +
            "ZS5jb22CC2V4YW1wbGUuY29tMAwGA1UdEwEB/wQCMAAwDQYJKoZIhvcNAQEMBQADggEBAGWztBqMKpFrKESN" +
            "RX6JXNm4lOXInxi2lWzD6dMtXD9X1KJuHqTjNvRNq135qkAj6Cks8H8OJpZabZAyeGnentyQw9DOZ2RGIaqJ" +
            "vYVpHuGw0tSrfPTUOCgCAaRHKocBu4yxMcj4+Spt3LU19V/GZQjH5kpAau0dkn5TqOJG/86801CVLopa1VM7" +
            "+AsC6qCDYA6oITsEbbv9UlyQgASwoXlYP6ziavGOdHsNHNCPYF3YBCEPPavxOYhOiXvi5a/JQKo/KeYVAGSB" +
            "WLgh0FiaS2JI+wMkYCqQSHtsFySy3itowjieFBspAJOm+sbsJeoapCkMgK1i74VAgoXPQ03Rp0E="
    )

    val commaDer = Base64.getDecoder().decode(
        "MIIDAjCCAeqgAwIBAgIJAPmf45UoEu67MA0GCSqGSIb3DQEBDAUAMC8xCzAJBgNVBAYTAkNaMQwwCgYDVQQK" +
            "EwNPcmcxEjAQBgNVBAMTCURvZSwgSm9objAeFw0yNjA2MTAxMTU3MzBaFw0zNjA2MDcxMTU3MzBaMC8x" +
            "CzAJBgNVBAYTAkNaMQwwCgYDVQQKEwNPcmcxEjAQBgNVBAMTCURvZSwgSm9objCCASIwDQYJKoZIhvcN" +
            "AQEBBQADggEPADCCAQoCggEBAIqtDO4v5xOgmTaXVlQ49dpyuVH7QFznnP634YAQIRaVu6gBP7e8iQpX" +
            "/EhEhNFM0jVXLYYuom3DGQD17eBRU7hjOnZ+CsUI2NZYYE0XJdpKnKHkT+r18ZX9rlmOcU9KXeGtIlk9" +
            "ehOsChWhJY6F10CIrBH0AsCx+UrE9N28+6pVIu+7b2nmzaXGvBHnxdzBlifcVFdG+3dB8wCOiv93cQnf" +
            "45Ov1lDQBqL/HFMQkcDahV40oCesxb/UnmPKqgYI3euIpf20FbYtIynaOycHZ+q4Z5dxnzoZS4Nyiw0w" +
            "NFbi+PDeq0Du2q5MhbA/4L8k+jWHUdz6c71u7VG4XoC0rrsCAwEAAaMhMB8wHQYDVR0OBBYEFOv0gUQr" +
            "hSmgMRmiEvHcGxw+soklMA0GCSqGSIb3DQEBDAUAA4IBAQCJ4D/5Dzdzc5g1MRNxQqjoAyv+CvfTcxrd" +
            "tPzJbVp9OauicBaW5zipZuEcBCKctU4+bbrIyZ1i7RD9jB6D8UfKFZGKewICxHkEYmWBYdZ3vyxb8qgr" +
            "+JBh167qQBnSHnA7IT0NCq/j72cSnT9Okk9OFb4V1bxbZLD5PzzaljC5MbO6oSH6ms7QW1XtSFl3qpkJ" +
            "bYktzJWRa2k4rYcuaLizFl0qiwoB9QlSlAiUND6MV5DdO8cVhOpfU3fGgZQdBJgiFZ5yIABFkjl0w4f6" +
            "dGkgBdKe1yU65iuvwnmmkwJ+aKPP5fo7e43YuB7DX3lzX1MRONFpwZL+kOeLBNC/XxN4"
    )

    test("parses all sections from a certificate DER") {
        val titles = extractCertificateDetails(der).map { it.title }
        listOf("General", "Subject", "Issuer", "Validity", "Public Key", "Extensions", "Fingerprints")
            .forEach { titles shouldContain it }
    }

    test("breaks the subject into named distinguished-name components") {
        val subject = extractCertificateDetails(der).first { it.title == "Subject" }
        val byLabel = subject.fields.associate { it.label to it.value }
        byLabel["Common Name (CN)"] shouldBe "Test Signer"
        byLabel["Organization (O)"] shouldBe "Org"
        byLabel["Organizational Unit (OU)"] shouldBe "Unit"
        byLabel["Country (C)"] shouldBe "CZ"
    }

    test("decodes common extensions and surfaces the subject alternative names") {
        val ext = extractCertificateDetails(der).first { it.title == "Extensions" }
        val text = ext.fields.joinToString("\n") { "${it.label} = ${it.value}" }
        text shouldContain "Key Usage"
        text shouldContain "Digital Signature"
        text shouldContain "Non Repudiation"
        text shouldContain "Subject Alternative Name"
        text shouldContain "test@example.com"
        text shouldContain "example.com"
        text shouldContain "Basic Constraints"
    }

    test("reports the public key and a SHA-256 fingerprint") {
        val sections = extractCertificateDetails(der)
        val key = sections.first { it.title == "Public Key" }.fields.associate { it.label to it.value }
        key["Algorithm"] shouldBe "RSA"
        key["Key Size"] shouldBe "2048 bit"
        sections.first { it.title == "Fingerprints" }.fields.map { it.label } shouldContain "SHA-256"
    }

    test("renders an in-value comma in a distinguished name without RFC 4514 escaping") {
        val subject = extractCertificateDetails(commaDer).first { it.title == "Subject" }
        val commonName = subject.fields.first { it.label == "Common Name (CN)" }.value
        commonName shouldBe "Doe, John"
    }

    test("decodes the subject key identifier to colon-separated hex") {
        val extensions = extractCertificateDetails(commaDer).first { it.title == "Extensions" }
        val keyId = extensions.fields.first { it.label == "Subject Key Identifier" }.value
        keyId shouldBe "EB:F4:81:44:2B:85:29:A0:31:19:A2:12:F1:DC:1B:1C:3E:B2:89:25"
    }
})
