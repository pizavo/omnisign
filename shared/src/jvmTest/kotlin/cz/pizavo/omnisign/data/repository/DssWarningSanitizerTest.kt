package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.repository.DssWarningSanitizer.WarningCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies the [DssWarningSanitizer] correctly classifies, groups, and summarises
 * raw DSS warning messages into user-friendly output.
 */
class DssWarningSanitizerTest : FunSpec({

	test("empty input produces empty output") {
		val result = DssWarningSanitizer.sanitize(emptyList())
		result.summaries.shouldBeEmpty()
		result.raw.shouldBeEmpty()
	}

	test("raw list is always preserved intact") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA",
			"something unexpected",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.raw.shouldContainExactly(raw)
	}

	test("REVOCATION_NOT_FOUND groups multiple certificates") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555FFFF6666",
			"No revocation found for the certificate C-1111222233334444555566667777888899990000AAAABBBB",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 certificates"
		result.summaries[0] shouldContain "CRL/OCSP"
	}

	test("REVOCATION_NOT_FOUND with a single certificate uses singular form") {
		val raw = listOf("No revocation found for the certificate C-ABCD1234")
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "1 certificate"
	}

	test("REVOCATION_UNTRUSTED_CHAIN patterns are matched") {
		val raw = listOf(
			"External revocation check is skipped for untrusted certificate : C-AAAA",
			"Revocation data is skipped for untrusted certificate chain! C-BBBB",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "untrusted chain"
	}

	test("REVOCATION_STATUS_UNKNOWN patterns are matched") {
		val raw = listOf(
			"The certificate 'C-ABCD1234' is not known to be not revoked!",
			"The certificate 'C-ABCD1234' does not contain a valid revocation data information!",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "Revocation status could not be confirmed"
	}

	test("REVOCATION_POE_MISSING pattern is matched") {
		val raw = listOf(
			"Revocation data is missing for one or more POE(s). NextUpdate time : 2026-09-18T14:45:18Z"
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "proof-of-existence"
	}

	test("FRESH_REVOCATION_MISSING pattern is matched") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]"
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "Fresh revocation data"
	}

	test("TIMESTAMP_UNTRUSTED pattern is matched") {
		val raw = listOf(
			"POE extraction is skipped for untrusted timestamp : T-AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555FFFF6666",
			"POE extraction is skipped for untrusted timestamp : T-1111222233334444555566667777888899990000AAAABBBB",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 timestamps"
		result.summaries[0] shouldContain "TSA"
	}

	test("CERTIFICATE_PARSE_ERROR groups different parse failures into one summary") {
		val raw = listOf(
			"Unable to load the alternative name. Reason : Invalid sequence length!",
			"Unable to parse the certificatePolicies extension 'BIHOMIHLMIHIBgAwgcMw...' : Unable to retrieve the ASN1Sequence",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "malformed extensions"
		result.summaries[0] shouldNotContain "BIHOMIHLMIHIBgAwgcMw"
	}

	test("duplicate raw messages in the same category are counted once per unique ID") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA",
			"No revocation found for the certificate C-AAAA",
			"No revocation found for the certificate C-BBBB",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 certificates"
	}

	test("unmatched messages pass through verbatim after categorized summaries") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA",
			"Some completely unknown DSS message",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 2
		result.summaries[0] shouldContain "CRL/OCSP"
		result.summaries[1] shouldBe "Some completely unknown DSS message"
	}

	test("mixed categories produce one summary per category in enum order") {
		val raw = listOf(
			"POE extraction is skipped for untrusted timestamp : T-FFFF",
			"No revocation found for the certificate C-AAAA",
			"Unable to load the alternative name. Reason : Invalid sequence length!",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 3
		result.summaries[0] shouldContain "CRL/OCSP"
		result.summaries[1] shouldContain "TSA"
		result.summaries[2] shouldContain "malformed extensions"
	}

	test("classify returns null for unknown messages") {
		DssWarningSanitizer.classify("Totally unknown message") shouldBe null
	}

	test("classify returns correct category for known patterns") {
		DssWarningSanitizer.classify(
			"No revocation found for the certificate C-AAAA"
		)?.first shouldBe WarningCategory.REVOCATION_NOT_FOUND

		DssWarningSanitizer.classify(
			"POE extraction is skipped for untrusted timestamp : T-BBBB"
		)?.first shouldBe WarningCategory.TIMESTAMP_UNTRUSTED

		DssWarningSanitizer.classify(
			"Unable to parse the certificatePolicies extension 'blob'"
		)?.first shouldBe WarningCategory.CERTIFICATE_PARSE_ERROR
	}

	test("long certificate IDs are shortened in the internal bucket") {
		val longId = "C-" + "A".repeat(64)
		val raw = listOf("No revocation found for the certificate $longId")
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "1 certificate"
	}

	test("CollectingStatusAlert compound message with untrusted chain is matched") {
		val raw = listOf(
			"Revocation data is missing for one or more certificate(s). " +
					"[C-AAAA: Revocation data is skipped for untrusted certificate chain!; " +
					"C-BBBB: Revocation data is skipped for untrusted certificate chain!]"
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "untrusted chain"
	}
	
	test("OCSP DSS Exception is classified as REVOCATION_NOT_FOUND") {
		val raw = listOf(
			"OCSP DSS Exception: Unable to retrieve OCSP response for certificate " +
					"with Id 'C-398F2F45F30C8052B4803A91EA4A37EB4361B67EB378FE75BDC462B3542D5A97' " +
					"from URL 'http://ocsp.cesnet-ca.cz/'. Reason : unknown tag 28 encountered"
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "CRL/OCSP"
		result.summaries[0] shouldContain "1 certificate"
	}
	
	test("Unable to retrieve OCSP response without prefix is classified as REVOCATION_NOT_FOUND") {
		DssWarningSanitizer.classify(
			"Unable to retrieve OCSP response for certificate with Id 'C-ABCD1234' from URL 'http://example.com/'"
		)?.first shouldBe WarningCategory.REVOCATION_NOT_FOUND
	}
	
	test("Unable to download CRL is classified as REVOCATION_NOT_FOUND") {
		DssWarningSanitizer.classify(
			"CRL DSS Exception: Unable to download CRL for certificate with Id 'C-ABCD1234'"
		)?.first shouldBe WarningCategory.REVOCATION_NOT_FOUND
	}
	
	test("OCSP and standard revocation messages group into one summary") {
		val raw = listOf(
			"OCSP DSS Exception: Unable to retrieve OCSP response for certificate " +
					"with Id 'C-AAAA1111' from URL 'http://ocsp.example.com/'.",
			"No revocation found for the certificate C-BBBB2222",
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 certificates"
		result.summaries[0] shouldContain "CRL/OCSP"
	}
	
	test("TSP_FAILURE pattern matches PKIFailureInfo warning") {
		val raw = listOf(
			"TSP Failure info: PKIFailureInfo: 0x4"
		)
		val result = DssWarningSanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "timestamp server"
	}
	
	test("TSP_FAILURE classify returns correct category") {
		DssWarningSanitizer.classify(
			"TSP Failure info: PKIFailureInfo: 0x4"
		)?.first shouldBe WarningCategory.TSP_FAILURE
		
		DssWarningSanitizer.classify(
			"No timestamp token has been retrieved (TSP Status : ...)"
		)?.first shouldBe WarningCategory.TSP_FAILURE
	}
})

