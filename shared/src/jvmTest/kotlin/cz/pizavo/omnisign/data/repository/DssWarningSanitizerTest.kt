package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.repository.DssWarningSanitizer.WarningCategory
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies the [DssWarningSanitizer] correctly classifies, groups, and summarizes
 * raw DSS warning messages into user-friendly output.
 */
class DssWarningSanitizerTest : FunSpec({

	val sanitizer = DssWarningSanitizer()

	val stalePoeDetail = "No revocation data found after the best signature time " +
			"[2026-07-09T09:21:41Z]! The nextUpdate available after : [2026-07-16T02:36:55Z]"
	val laterStalePoeDetail = "No revocation data found after the best signature time " +
			"[2026-07-09T09:21:41Z]! The nextUpdate available after : [2026-07-23T02:36:55Z]"
	val undatedStalePoeDetail = "No revocation data found after the best signature time [2026-07-09T09:21:41Z]!"
	val absentPoeDetail = "No revocation data found for certificate!"

	val signingSuppressed = setOf(
		WarningCategory.REVOCATION_NOT_FOUND,
		WarningCategory.FRESH_REVOCATION_MISSING,
		WarningCategory.CERTIFICATE_PARSE_ERROR,
	)

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

	test("POE warning about revocation older than the timestamp is REVOCATION_POE_STALE") {
		val raw = listOf("Revocation data is missing for one or more POE(s). [C-660A98F1: $stalePoeDetail]")
		val result = sanitizer.sanitize(raw)
		result.categories shouldBe setOf(WarningCategory.REVOCATION_POE_STALE)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "1 certificate"
		result.summaries[0] shouldContain "predates the signature timestamp"
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf("C-660A98F1")
	}

	test("REVOCATION_POE_STALE keeps its category and counts every certificate of a compound message") {
		val raw = listOf(
			"Revocation data is missing for one or more POE(s). " +
					"[C-660A98F1: $stalePoeDetail; C-AB12CD34: $stalePoeDetail]"
		)
		val result = sanitizer.sanitize(raw)
		result.categories shouldBe setOf(WarningCategory.REVOCATION_POE_STALE)
		result.summaries[0] shouldContain "2 certificates"
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf("C-660A98F1", "C-AB12CD34")
	}

	test("the stale POE summary names the time by which newer revocation data is guaranteed") {
		val raw = listOf("Revocation data is missing for one or more POE(s). [C-660A98F1: $stalePoeDetail]")
		val result = sanitizer.sanitize(raw)
		result.summaries[0] shouldContain "guarantees newer revocation data by 2026-07-16T02:36:55Z"
		result.summaries[0] shouldContain "closes the gap"
	}

	test("the stale POE summary reports the latest guaranteed time when certificates refresh separately") {
		val raw = listOf(
			"Revocation data is missing for one or more POE(s). " +
					"[C-660A98F1: $stalePoeDetail; C-AB12CD34: $laterStalePoeDetail]"
		)
		val result = sanitizer.sanitize(raw)
		result.summaries[0] shouldContain "guarantees newer revocation data by 2026-07-23T02:36:55Z"
		result.summaries[0] shouldNotContain "2026-07-16T02:36:55Z"
	}

	test("the stale POE summary falls back to generic advice when DSS reports no due time") {
		val raw = listOf("Revocation data is missing for one or more POE(s). [C-660A98F1: $undatedStalePoeDetail]")
		val result = sanitizer.sanitize(raw)
		result.categories shouldBe setOf(WarningCategory.REVOCATION_POE_STALE)
		result.summaries[0] shouldContain "once newer revocation data is published"
		result.summaries[0] shouldNotContain "guarantees"
	}

	test("a recognized warning is a translatable Keyed summary carrying the count phrase as its argument") {
		val raw = listOf("Revocation data is missing for one or more certificate(s). [C-AAAA1111: $absentPoeDetail]")
		val keyed = sanitizer.sanitize(raw).annotatedSummaries[0].summary
			.shouldBeInstanceOf<LocalizableText.Keyed>()
		keyed.key shouldBe MessageKey.WARNING_REVOCATION_NOT_FOUND
		keyed.args shouldContainExactly listOf("1 certificate")
	}

	test("a dated stale-POE warning is Keyed with the by-time key and the due time as its second argument") {
		val raw = listOf("Revocation data is missing for one or more POE(s). [C-660A98F1: $stalePoeDetail]")
		val keyed = sanitizer.sanitize(raw).annotatedSummaries[0].summary
			.shouldBeInstanceOf<LocalizableText.Keyed>()
		keyed.key shouldBe MessageKey.WARNING_REVOCATION_POE_STALE_BY_TIME
		keyed.args shouldContainExactly listOf("1 certificate", "2026-07-16T02:36:55Z")
	}

	test("an undated stale-POE warning is Keyed with the generic key and only the count phrase") {
		val raw = listOf("Revocation data is missing for one or more POE(s). [C-660A98F1: $undatedStalePoeDetail]")
		val keyed = sanitizer.sanitize(raw).annotatedSummaries[0].summary
			.shouldBeInstanceOf<LocalizableText.Keyed>()
		keyed.key shouldBe MessageKey.WARNING_REVOCATION_POE_STALE_GENERIC
		keyed.args shouldContainExactly listOf("1 certificate")
	}

	test("an unmatched DSS message is kept as a verbatim Literal") {
		val summary = sanitizer.sanitize(listOf("Some completely unknown DSS message")).annotatedSummaries[0].summary
		summary.shouldBeInstanceOf<LocalizableText.Literal>().value shouldBe "Some completely unknown DSS message"
	}

	test("POE warning with no revocation data at all is REVOCATION_POE_MISSING") {
		val raw = listOf(
			"Revocation data is missing for one or more POE(s). [C-AAAA1111: $absentPoeDetail]"
		)
		val result = sanitizer.sanitize(raw)
		result.categories shouldBe setOf(WarningCategory.REVOCATION_POE_MISSING)
		result.summaries[0] shouldContain "proof-of-existence is missing"
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf("C-AAAA1111")
	}

	test("POE warning mixing absent and stale revocation data reports the absent data") {
		val raw = listOf(
			"Revocation data is missing for one or more POE(s). " +
					"[C-AAAA1111: $absentPoeDetail; C-BBBB2222: $stalePoeDetail]"
		)
		val result = sanitizer.sanitize(raw)
		result.categories shouldBe setOf(WarningCategory.REVOCATION_POE_MISSING)
	}

	test("the missing-revocation-data status keeps its own category") {
		val raw = listOf(
			"Revocation data is missing for one or more certificate(s). [C-AAAA1111: $absentPoeDetail]"
		)
		val result = sanitizer.sanitize(raw)
		result.categories shouldBe setOf(WarningCategory.REVOCATION_NOT_FOUND)
		result.summaries[0] shouldContain "CRL/OCSP"
	}

	test("FRESH_REVOCATION_MISSING pattern is matched") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]"
		)
		val result = sanitizer.sanitize(raw)
		result.summaries shouldHaveSize 1
		result.summaries[0] shouldContain "in the signing chain"
		result.summaries[0] shouldContain "does not cover the moment of signing"
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
		result.summaries[0] shouldContain "in the signing chain"
	}
	
	test("REVOCATION_NOT_FOUND is not revocation-related") {
		WarningCategory.REVOCATION_NOT_FOUND.isRevocationRelated shouldBe false
	}

	test("classify recognises revocation rejected for postdating the certificate") {
		sanitizer.classify(
			"The revocation 'R-1234ABCD' was not issued during the validity period of the certificate! " +
				"Certificate: C-ABCD1234"
		)?.first shouldBe WarningCategory.REVOCATION_AFTER_CERTIFICATE_EXPIRY
	}

	test("revocation-after-expiry summarises with its own key and the affected certificate") {
		val raw = listOf(
			"The revocation 'R-1234ABCD' was not issued during the validity period of the certificate! " +
				"Certificate: C-ABCD1234"
		)
		val result = sanitizer.sanitize(raw)
		val keyed = result.annotatedSummaries[0].summary.shouldBeInstanceOf<LocalizableText.Keyed>()
		keyed.key shouldBe MessageKey.WARNING_REVOCATION_AFTER_CERTIFICATE_EXPIRY
		keyed.args shouldContainExactly listOf("1 certificate")
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf("C-ABCD1234")
	}

	test("categories that leave the output below its target level block long-term material") {
		WarningCategory.REVOCATION_NOT_FOUND.blocksLongTermMaterial shouldBe true
		WarningCategory.REVOCATION_AFTER_CERTIFICATE_EXPIRY.blocksLongTermMaterial shouldBe true
		WarningCategory.REVOCATION_STATUS_UNKNOWN.blocksLongTermMaterial shouldBe true
		WarningCategory.REVOCATION_POE_MISSING.blocksLongTermMaterial shouldBe true
	}

	test("data that was embedded but does not yet cover its time does not block long-term material") {
		WarningCategory.REVOCATION_POE_STALE.blocksLongTermMaterial shouldBe false
		WarningCategory.FRESH_REVOCATION_MISSING.blocksLongTermMaterial shouldBe false
		WarningCategory.TSP_FAILURE.blocksLongTermMaterial shouldBe false
	}

	test("sanitize reports longTermMaterialMissing for revocation that postdates the certificate") {
		val raw = listOf(
			"The revocation 'R-1234ABCD' was not issued during the validity period of the certificate! " +
				"Certificate: C-ABCD1234"
		)
		sanitizer.sanitize(raw).longTermMaterialMissing shouldBe true
	}

	test("sanitize reports longTermMaterialMissing when revocation could not be retrieved") {
		val raw = listOf("Revocation data is missing for one or more certificate(s). [C-ABCD1234: detail]")
		sanitizer.sanitize(raw).longTermMaterialMissing shouldBe true
	}

	test("sanitize does not report longTermMaterialMissing for stale-but-present revocation data") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]"
		)
		sanitizer.sanitize(raw).longTermMaterialMissing shouldBe false
	}

	test("sanitize reports revocationNotRefreshed for stale-but-present revocation data") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]"
		)
		val result = sanitizer.sanitize(raw)
		result.revocationNotRefreshed shouldBe true
		result.longTermMaterialMissing shouldBe false
	}

	test("sanitize does not report revocationNotRefreshed when revocation could not be retrieved at all") {
		val raw = listOf("Revocation data is missing for one or more certificate(s). [C-ABCD1234: detail]")
		sanitizer.sanitize(raw).revocationNotRefreshed shouldBe false
	}

	test("a suppressed FRESH_REVOCATION_MISSING does not report revocationNotRefreshed") {
		val raw = listOf(
			"Fresh revocation data is missing for one or more certificate(s). [C-AAAA: detail]"
		)
		val result = sanitizer.sanitize(
			raw,
			suppressedCategories = setOf(WarningCategory.FRESH_REVOCATION_MISSING),
		)
		result.revocationNotRefreshed shouldBe false
	}

	test("a suppressed category does not report longTermMaterialMissing") {
		val raw = listOf("Revocation data is missing for one or more certificate(s). [C-ABCD1234: detail]")
		val result = sanitizer.sanitize(
			raw,
			suppressedCategories = setOf(WarningCategory.REVOCATION_NOT_FOUND),
		)
		result.longTermMaterialMissing shouldBe false
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
		result.annotatedSummaries[0].summary.english() shouldContain "2 certificates"
	}
	
	test("annotatedSummaries for unmatched messages have empty affectedIds") {
		val raw = listOf("Some completely unknown DSS message")
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].affectedIds.shouldBeEmpty()
		result.annotatedSummaries[0].summary.english() shouldBe "Some completely unknown DSS message"
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
			"Fresh revocation data is missing for one or more certificate(s).",
			"Unable to load the alternative name. Reason : Invalid sequence length!",
		)
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 2
		result.annotatedSummaries[0].summary.english() shouldContain "in the signing chain"
		result.annotatedSummaries[0].affectedIds.shouldBeEmpty()
		result.annotatedSummaries[1].summary.english() shouldContain "malformed extensions"
		result.annotatedSummaries[1].affectedIds.shouldBeEmpty()
	}

	test("every certificate of a compound message lands in affectedIds") {
		val raw = listOf(
			"Revocation data is missing for one or more certificate(s). " +
					"[C-AAAA: Revocation data is skipped for untrusted certificate chain!; " +
					"C-BBBB: Revocation data is skipped for untrusted certificate chain!]"
		)
		val result = sanitizer.sanitize(raw)
		result.annotatedSummaries shouldHaveSize 1
		result.annotatedSummaries[0].affectedIds shouldContainExactly listOf("C-AAAA", "C-BBBB")
		result.annotatedSummaries[0].summary.english() shouldContain "2 certificates"
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
		result.annotatedSummaries[0].summary.english() shouldContain "malformed extensions"
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

	test("REVOCATION_POE_STALE is not revocation-related") {
		WarningCategory.REVOCATION_POE_STALE.isRevocationRelated shouldBe false
	}

	test("stale POE data is reported to the signer but raises no confirmation prompt") {
		val raw = listOf("Revocation data is missing for one or more POE(s). [C-660A98F1: $stalePoeDetail]")
		val result = sanitizer.sanitize(raw, suppressedCategories = signingSuppressed)
		result.annotatedSummaries shouldHaveSize 1
		result.summaries[0] shouldContain "predates the signature timestamp"
		result.hasRevocationWarnings shouldBe false
	}

	test("genuinely absent POE revocation data is reported and raises the confirmation prompt") {
		val raw = listOf("Revocation data is missing for one or more POE(s). [C-AAAA1111: $absentPoeDetail]")
		val result = sanitizer.sanitize(raw, suppressedCategories = signingSuppressed)
		result.annotatedSummaries shouldHaveSize 1
		result.summaries[0] shouldContain "proof-of-existence is missing"
		result.hasRevocationWarnings shouldBe true
	}

	test("a suppressed revocation category does not raise hasRevocationWarnings") {
		val raw = listOf("The certificate 'C-ABCD1234' is not known to be not revoked!")
		val result = sanitizer.sanitize(
			raw,
			suppressedCategories = setOf(WarningCategory.REVOCATION_STATUS_UNKNOWN),
		)
		result.annotatedSummaries.shouldBeEmpty()
		result.categories shouldBe setOf(WarningCategory.REVOCATION_STATUS_UNKNOWN)
		result.hasRevocationWarnings shouldBe false
	}
})

