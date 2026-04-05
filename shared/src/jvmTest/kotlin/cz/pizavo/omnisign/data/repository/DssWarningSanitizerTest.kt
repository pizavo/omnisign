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
 * Verifies the [DssWarningSanitizer] correctly classifies, groups, and summarizes
 * raw DSS warning messages into user-friendly output.
 */
class DssWarningSanitizerTest : FunSpec({

	val sanitizer = DssWarningSanitizer()

	test("empty input produces empty output") {
		val result = sanitizer.sanitize(emptyList())
		result.summaries.shouldBeEmpty()
		result.raw.shouldBeEmpty()
	}

	test("raw list is always preserved intact") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA",
			"something unexpected",
		)
		val result = sanitizer.sanitize(raw)
		result.raw.shouldContainExactly(raw)
	}

	test("REVOCATION_NOT_FOUND groups multiple certificates") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555FFFF6666",
			"No revocation found for the certificate C-1111222233334444555566667777888899990000AAAABBBB",
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 certificates"
		result.summaries[0] shouldContain "CRL/OCSP"
	}

	test("REVOCATION_NOT_FOUND with a single certificate uses singular form") {
		val raw = listOf("No revocation found for the certificate C-ABCD1234")
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "1 certificate"
	}

	test("REVOCATION_UNTRUSTED_CHAIN patterns are matched") {
		val raw = listOf(
			"External revocation check is skipped for untrusted certificate : C-AAAA",
			"Revocation data is skipped for untrusted certificate chain! C-BBBB",
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "untrusted chain"
	}

	test("REVOCATION_STATUS_UNKNOWN patterns are matched") {
		val raw = listOf(
			"The certificate 'C-ABCD1234' is not known to be not revoked!",
			"The certificate 'C-ABCD1234' does not contain a valid revocation data information!",
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "Revocation status could not be confirmed"
	}

	test("REVOCATION_POE_MISSING pattern is matched") {
		val raw = listOf(
			"Revocation data is missing for one or more POE(s). NextUpdate time : 2026-09-18T14:45:18Z"
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "proof-of-existence"
	}

	test("FRESH_REVOCATION_MISSING pattern is matched") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]"
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "Fresh revocation data"
	}

	test("TIMESTAMP_UNTRUSTED pattern is matched") {
		val raw = listOf(
			"POE extraction is skipped for untrusted timestamp : T-AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555FFFF6666",
			"POE extraction is skipped for untrusted timestamp : T-1111222233334444555566667777888899990000AAAABBBB",
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 timestamps"
		result.summaries[0] shouldContain "TSA"
	}

	test("CERTIFICATE_PARSE_ERROR groups different parse failures into one summary") {
		val raw = listOf(
			"Unable to load the alternative name. Reason : Invalid sequence length!",
			"Unable to parse the certificatePolicies extension 'BIHOMIHLMIHIBgAwgcMw...' : Unable to retrieve the ASN1Sequence",
		)
		val result = sanitizer.sanitize(raw)
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
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 certificates"
	}

	test("unmatched messages pass through verbatim after categorized summaries") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA",
			"Some completely unknown DSS message",
		)
		val result = sanitizer.sanitize(raw)
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
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 3
		result.summaries[0] shouldContain "CRL/OCSP"
		result.summaries[1] shouldContain "TSA"
		result.summaries[2] shouldContain "malformed extensions"
	}

	test("classify returns null for unknown messages") {
		sanitizer.classify("Totally unknown message") shouldBe null
	}

	test("classify returns correct category for known patterns") {
		sanitizer.classify(
			"No revocation found for the certificate C-AAAA"
		)?.first shouldBe WarningCategory.REVOCATION_NOT_FOUND

		sanitizer.classify(
			"POE extraction is skipped for untrusted timestamp : T-BBBB"
		)?.first shouldBe WarningCategory.TIMESTAMP_UNTRUSTED

		sanitizer.classify(
			"Unable to parse the certificatePolicies extension 'blob'"
		)?.first shouldBe WarningCategory.CERTIFICATE_PARSE_ERROR
	}

	test("full certificate IDs are preserved in affectedIds") {
		val longId = "C-" + "A".repeat(64)
		val raw = listOf("No revocation found for the certificate $longId")
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "1 certificate"
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf(longId)
	}

	test("CollectingStatusAlert compound message with untrusted chain is matched") {
		val raw = listOf(
			"Revocation data is missing for one or more certificate(s). " +
					"[C-AAAA: Revocation data is skipped for untrusted certificate chain!; " +
					"C-BBBB: Revocation data is skipped for untrusted certificate chain!]"
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "untrusted chain"
	}
	
	test("OCSP DSS Exception is classified as REVOCATION_NOT_FOUND") {
		val raw = listOf(
			"OCSP DSS Exception: Unable to retrieve OCSP response for certificate " +
					"with Id 'C-398F2F45F30C8052B4803A91EA4A37EB4361B67EB378FE75BDC462B3542D5A97' " +
					"from URL 'http://ocsp.cesnet-ca.cz/'. Reason : unknown tag 28 encountered"
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "CRL/OCSP"
		result.summaries[0] shouldContain "1 certificate"
	}
	
	test("Unable to retrieve OCSP response without prefix is classified as REVOCATION_NOT_FOUND") {
		sanitizer.classify(
			"Unable to retrieve OCSP response for certificate with Id 'C-ABCD1234' from URL 'http://example.com/'"
		)?.first shouldBe WarningCategory.REVOCATION_NOT_FOUND
	}
	
	test("Unable to download CRL is classified as REVOCATION_NOT_FOUND") {
		sanitizer.classify(
			"CRL DSS Exception: Unable to download CRL for certificate with Id 'C-ABCD1234'"
		)?.first shouldBe WarningCategory.REVOCATION_NOT_FOUND
	}
	
	test("OCSP and standard revocation messages group into one summary") {
		val raw = listOf(
			"OCSP DSS Exception: Unable to retrieve OCSP response for certificate " +
					"with Id 'C-AAAA1111' from URL 'http://ocsp.example.com/'.",
			"No revocation found for the certificate C-BBBB2222",
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "2 certificates"
		result.summaries[0] shouldContain "CRL/OCSP"
	}
	
	test("TSP_FAILURE pattern matches PKIFailureInfo warning") {
		val raw = listOf(
			"TSP Failure info: PKIFailureInfo: 0x4"
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "timestamp server"
	}
	
	test("TSP_FAILURE classify returns correct category") {
		sanitizer.classify(
			"TSP Failure info: PKIFailureInfo: 0x4"
		)?.first shouldBe WarningCategory.TSP_FAILURE
		
		sanitizer.classify(
			"No timestamp token has been retrieved (TSP Status : ...)"
		)?.first shouldBe WarningCategory.TSP_FAILURE
	}
	
	test("FRESH_REVOCATION_MISSING is not revocation-related") {
		WarningCategory.FRESH_REVOCATION_MISSING.isRevocationRelated shouldBe false
	}
	
	test("sanitize with only FRESH_REVOCATION_MISSING has hasRevocationWarnings false") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]"
		)
		val result = sanitizer.sanitize(raw)
		result.hasRevocationWarnings shouldBe false
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "Fresh revocation data"
	}
	
	test("REVOCATION_NOT_FOUND is not revocation-related") {
		WarningCategory.REVOCATION_NOT_FOUND.isRevocationRelated shouldBe false
	}
	
	test("REVOCATION_STATUS_UNKNOWN is revocation-related") {
		WarningCategory.REVOCATION_STATUS_UNKNOWN.isRevocationRelated shouldBe true
	}
	
	test("sanitize with only REVOCATION_NOT_FOUND has hasRevocationWarnings false") {
		val raw = listOf("No revocation found for the certificate C-ABCD1234")
		val result = sanitizer.sanitize(raw)
		result.hasRevocationWarnings shouldBe false
	}
	
	test("annotatedSummaries carry sorted affectedIds for grouped category") {
		val raw = listOf(
			"No revocation found for the certificate C-BBBB",
			"No revocation found for the certificate C-AAAA",
		)
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf("C-AAAA", "C-BBBB")
		result.annotatedSummaries[0].summary shouldContain "2 certificates"
	}
	
	test("annotatedSummaries for unmatched messages have empty affectedIds") {
		val raw = listOf("Some completely unknown DSS message")
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].affectedIds.shouldBeEmpty()
		result.annotatedSummaries[0].summary shouldBe "Some completely unknown DSS message"
	}
	
	test("annotatedSummaries carry timestamp IDs for TIMESTAMP_UNTRUSTED") {
		val raw = listOf(
			"POE extraction is skipped for untrusted timestamp : T-FFFF",
			"POE extraction is skipped for untrusted timestamp : T-AAAA",
		)
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf("T-AAAA", "T-FFFF")
	}
	
	test("affectedIds exclude placeholder entries for categories without extractable IDs") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]",
			"Unable to load the alternative name. Reason : Invalid sequence length!",
		)
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 2
		result.annotatedSummaries[0].summary shouldContain "Fresh revocation data"
		result.annotatedSummaries[0].affectedIds.shouldBeEmpty()
		result.annotatedSummaries[1].summary shouldContain "malformed extensions"
		result.annotatedSummaries[1].affectedIds.shouldBeEmpty()
	}
	
	test("certIdNames are propagated to annotatedSummaries idNames") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA",
			"No revocation found for the certificate C-BBBB",
		)
		val names = mapOf("C-AAAA" to "PostSignum Qualified CA 4", "C-BBBB" to "CESNET CA")
		val result = sanitizer.sanitize(raw, names)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].idNames shouldBe mapOf(
			"C-AAAA" to "PostSignum Qualified CA 4",
			"C-BBBB" to "CESNET CA",
		)
	}
	
	test("idNames only include IDs present in affectedIds") {
		val raw = listOf("No revocation found for the certificate C-AAAA")
		val names = mapOf("C-AAAA" to "Known Cert", "C-ZZZZ" to "Other Cert")
		val result = sanitizer.sanitize(raw, names)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].idNames shouldBe mapOf("C-AAAA" to "Known Cert")
	}
	
	test("idNames are empty when no certIdNames are provided") {
		val raw = listOf("No revocation found for the certificate C-AAAA")
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].idNames shouldBe emptyMap()
	}
	
	test("suppressedCategories excludes matching categories from annotatedSummaries") {
		val raw = listOf(
			"No revocation found for the certificate C-AAAA",
			"Fresh revocation data is missing for one or more certificate(s).",
			"Unable to load the alternative name",
		)
		val result = sanitizer.sanitize(
			raw,
			suppressedCategories = setOf(
				WarningCategory.REVOCATION_NOT_FOUND,
				WarningCategory.FRESH_REVOCATION_MISSING,
			),
		)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].summary shouldContain "malformed extensions"
	}
	
	test("suppressedCategories still records categories in the categories set") {
		val raw = listOf("No revocation found for the certificate C-AAAA")
		val result = sanitizer.sanitize(
			raw,
			suppressedCategories = setOf(WarningCategory.REVOCATION_NOT_FOUND),
		)
		result.annotatedSummaries.shouldBeEmpty()
		result.categories shouldBe setOf(WarningCategory.REVOCATION_NOT_FOUND)
		result.raw shouldHaveSize 1
	}
	
	test("suppressedCategories with empty set behaves like default") {
		val raw = listOf("No revocation found for the certificate C-AAAA")
		val result = sanitizer.sanitize(raw, suppressedCategories = emptySet())
		result.annotatedSummaries shouldHaveSize 1
		result.summaries[0] shouldContain "CRL/OCSP"
	}
})

