package cz.pizavo.omnisign.domain.model.validation.json

import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.TimestampValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.time.Instant

/**
 * Verifies JSON serialization of [ValidationReport] via the shared DTO mappers.
 */
class JsonReportSerializationTest : FunSpec({

    val sampleCert = CertificateInfo(
        subjectDN = "CN=Alice",
        issuerDN = "CN=RootCA",
        serialNumber = "DEADBEEF",
        validFrom = Instant.parse("2025-01-01T00:00:00Z"),
        validTo = Instant.parse("2027-12-31T23:59:59Z"),
        keyUsages = listOf("NON_REPUDIATION"),
        isQualified = true,
        publicKeyAlgorithm = "RSA",
        sha256Fingerprint = "AA:BB:CC",
    )

    val sampleReport = ValidationReport(
        documentName = "contract.pdf",
        validationTime = Instant.parse("2026-03-28T12:00:00Z"),
        overallResult = ValidationResult.VALID,
        signatures = listOf(
            SignatureValidationResult(
                signatureId = "sig-1",
                indication = ValidationIndication.TOTAL_PASSED,
                signedBy = "Alice",
                signatureLevel = "PAdES-BASELINE-LTA",
                signatureTime = Instant.parse("2026-03-28T11:00:00Z"),
                certificate = sampleCert,
                hashAlgorithm = "SHA256",
                encryptionAlgorithm = "RSA",
                errors = listOf("err1"),
                warnings = listOf("warn1"),
                timestamps = listOf(
                    TimestampValidationResult(
                        timestampId = "ts-1",
                        type = "Signature timestamp",
                        indication = ValidationIndication.TOTAL_PASSED,
                        productionTime = Instant.parse("2026-03-28T11:00:01Z"),
                        tsaSubjectDN = "CN=TSA",
                    )
                ),
            )
        ),
        timestamps = listOf(
            TimestampValidationResult(
                timestampId = "ts-doc",
                type = "Document timestamp",
                indication = ValidationIndication.INDETERMINATE,
                subIndication = "NO_POE",
                productionTime = Instant.parse("2026-03-28T11:30:00Z"),
            )
        ),
        tlWarnings = listOf("TL refresh failed"),
    )

    test("toJsonReport maps all top-level fields") {
        val dto = sampleReport.toJsonReport()

        dto.documentName shouldBe "contract.pdf"
        dto.overallResult shouldBe "VALID"
        dto.signatures.size shouldBe 1
        dto.timestamps.size shouldBe 1
        dto.tlWarnings shouldBe listOf("TL refresh failed")
    }

    test("toJsonReport maps signature fields") {
        val sig = sampleReport.toJsonReport().signatures.first()

        sig.signatureId shouldBe "sig-1"
        sig.indication shouldBe "TOTAL_PASSED"
        sig.signedBy shouldBe "Alice"
        sig.signatureLevel shouldBe "PAdES-BASELINE-LTA"
        sig.hashAlgorithm shouldBe "SHA256"
        sig.encryptionAlgorithm shouldBe "RSA"
        sig.errors shouldBe listOf("err1")
        sig.warnings shouldBe listOf("warn1")
        sig.timestamps.size shouldBe 1
    }

    test("toJsonReport maps certificate fields") {
        val cert = sampleReport.toJsonReport().signatures.first().certificate

        cert.subjectDN shouldBe "CN=Alice"
        cert.issuerDN shouldBe "CN=RootCA"
        cert.serialNumber shouldBe "DEADBEEF"
        cert.keyUsages shouldBe listOf("NON_REPUDIATION")
        cert.isQualified shouldBe true
        cert.publicKeyAlgorithm shouldBe "RSA"
        cert.sha256Fingerprint shouldBe "AA:BB:CC"
    }

    test("toJsonReport maps summary counters") {
        val summary = sampleReport.toJsonReport().summary!!

        summary.total shouldBe 1
        summary.passed shouldBe 1
        summary.failed shouldBe 0
        summary.indeterminate shouldBe 0
    }

    test("toJsonString produces valid non-blank JSON with expected keys") {
        val json = sampleReport.toJsonReport().toJsonString()

        json.shouldNotBeBlank()
        json shouldContain "\"documentName\""
        json shouldContain "contract.pdf"
        json shouldContain "\"signatures\""
        json shouldContain "\"certificate\""
        json shouldContain "\"summary\""
        json shouldContain "\"tlWarnings\""
    }

    test("toJsonReport maps document-level timestamp with sub-indication") {
        val ts = sampleReport.toJsonReport().timestamps.first()

        ts.timestampId shouldBe "ts-doc"
        ts.type shouldBe "Document timestamp"
        ts.indication shouldBe "INDETERMINATE"
        ts.subIndication shouldBe "NO_POE"
    }
})

