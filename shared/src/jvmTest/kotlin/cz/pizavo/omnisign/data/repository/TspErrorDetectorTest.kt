package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [TspErrorDetector] verifying TSP exception detection,
 * PKIFailureInfo parsing, and user-friendly message generation.
 */
class TspErrorDetectorTest : FunSpec({

	val detector = TspErrorDetector()

	test("isTspException returns true for 'No timestamp token' message") {
		val ex = RuntimeException("No timestamp token has been retrieved (TSP Status : ...)")
		detector.isTspException(ex) shouldBe true
	}

	test("isTspException returns true when TSP failure is in a nested cause") {
		val root = RuntimeException("No timestamp token has been retrieved")
		val wrapper = RuntimeException("Signing failed", root)
		detector.isTspException(wrapper) shouldBe true
	}

	test("isTspException returns true for TSP Failure message") {
		val ex = RuntimeException("TSP Failure info: PKIFailureInfo: 0x4")
		detector.isTspException(ex) shouldBe true
	}

	test("isTspException returns false for unrelated exception") {
		val ex = RuntimeException("Certificate not found")
		detector.isTspException(ex) shouldBe false
	}

	test("isTspException returns false for null message") {
		val ex = RuntimeException()
		detector.isTspException(ex) shouldBe false
	}

	test("parsePkiFailureReason returns badAlg for 0x1") {
		val ex = RuntimeException("PKIFailureInfo: 0x1")
		val reason = detector.parsePkiFailureReason(ex)
		reason.shouldNotBeNull()
		reason shouldContain "badAlg"
	}

	test("parsePkiFailureReason returns badRequest for 0x4") {
		val ex = RuntimeException("PKIFailureInfo: 0x4")
		val reason = detector.parsePkiFailureReason(ex)
		reason.shouldNotBeNull()
		reason shouldContain "badRequest"
	}

	test("parsePkiFailureReason returns badDataFormat for 0x20") {
		val ex = RuntimeException("PKIFailureInfo: 0x20")
		val reason = detector.parsePkiFailureReason(ex)
		reason.shouldNotBeNull()
		reason shouldContain "badDataFormat"
	}

	test("parsePkiFailureReason returns systemFailure for 0x2000000") {
		val ex = RuntimeException("PKIFailureInfo: 0x2000000")
		val reason = detector.parsePkiFailureReason(ex)
		reason.shouldNotBeNull()
		reason shouldContain "systemFailure"
	}

	test("parsePkiFailureReason returns unknown for unmapped code") {
		val ex = RuntimeException("PKIFailureInfo: 0xFF")
		val reason = detector.parsePkiFailureReason(ex)
		reason.shouldNotBeNull()
		reason shouldContain "unknown failure code"
		reason shouldContain "0xFF"
	}

	test("parsePkiFailureReason returns null when no PKIFailureInfo is present") {
		val ex = RuntimeException("No timestamp token has been retrieved")
		detector.parsePkiFailureReason(ex).shouldBeNull()
	}

	test("parsePkiFailureReason finds code in nested cause") {
		val root = RuntimeException("PKIFailureInfo: 0x4")
		val wrapper = RuntimeException("No timestamp token has been retrieved", root)
		val reason = detector.parsePkiFailureReason(wrapper)
		reason.shouldNotBeNull()
		reason shouldContain "badRequest"
	}

	test("buildUserMessage includes TSA URL and decoded reason") {
		val ex = RuntimeException("No timestamp token has been retrieved (PKIFailureInfo: 0x4)")
		val msg = detector.buildUserMessage(ex, "https://tsa.example.com/timestamp")
		msg shouldContain "https://tsa.example.com/timestamp"
		msg shouldContain "badRequest"
		msg shouldContain "rejected the request"
	}

	test("buildUserMessage without TSA URL omits parentheses") {
		val ex = RuntimeException("No timestamp token has been retrieved (PKIFailureInfo: 0x4)")
		val msg = detector.buildUserMessage(ex, null)
		msg shouldContain "rejected the request"
		msg shouldContain "badRequest"
	}

	test("buildUserMessage with no PKIFailureInfo gives generic message") {
		val ex = RuntimeException("No timestamp token has been retrieved")
		val msg = detector.buildUserMessage(ex, "https://tsa.example.com/timestamp")
		msg shouldContain "failed to produce a timestamp token"
		msg shouldContain "https://tsa.example.com/timestamp"
	}

	test("isTspException handles real DSS-style message") {
		val ex = RuntimeException(
			"No timestamp token has been retrieved (TSP Status : " +
				"TimeStampReq: Asn1Exception: ASN.1 decode error @ offset 0:" +
				"Unexpected end-of-buffer encountered. / PKIFailureInfo: 0x4)"
		)
		detector.isTspException(ex) shouldBe true
	}

	test("parsePkiFailureReason parses 0x4 from real DSS-style message") {
		val ex = RuntimeException(
			"No timestamp token has been retrieved (TSP Status : " +
				"TimeStampReq: Asn1Exception: ASN.1 decode error @ offset 0:" +
				"Unexpected end-of-buffer encountered. / PKIFailureInfo: 0x4)"
		)
		val reason = detector.parsePkiFailureReason(ex)
		reason.shouldNotBeNull()
	}

	test("isTspException returns true for 'Invalid TSP response' with malformed body") {
		val ex = RuntimeException(
			"Invalid TSP response : malformed timestamp response: " +
				"java.lang.IllegalArgumentException: failed to construct sequence from byte[]: " +
				"corrupted stream - out of bounds length found: 108 >= 18"
		)
		detector.isTspException(ex) shouldBe true
	}

	test("isTspException returns true when malformed indicator is in nested cause") {
		val root = IllegalArgumentException(
			"failed to construct sequence from byte[]: corrupted stream - out of bounds length found: 108 >= 18"
		)
		val wrapper = RuntimeException("Invalid TSP response : malformed timestamp response", root)
		detector.isTspException(wrapper) shouldBe true
	}

	test("isMalformedResponse returns true for corrupted stream exception") {
		val ex = RuntimeException(
			"Invalid TSP response : malformed timestamp response: " +
				"java.lang.IllegalArgumentException: failed to construct sequence from byte[]: " +
				"corrupted stream - out of bounds length found: 108 >= 18"
		)
		detector.isMalformedResponse(ex) shouldBe true
	}

	test("isMalformedResponse returns false for normal PKIFailureInfo error") {
		val ex = RuntimeException("No timestamp token has been retrieved (PKIFailureInfo: 0x4)")
		detector.isMalformedResponse(ex) shouldBe false
	}

	test("buildUserMessage returns malformed hint for corrupted TSP response") {
		val ex = RuntimeException(
			"Invalid TSP response : malformed timestamp response: " +
				"java.lang.IllegalArgumentException: failed to construct sequence from byte[]: " +
				"corrupted stream - out of bounds length found: 108 >= 18"
		)
		val msg = detector.buildUserMessage(ex, "https://tsa.example.com/timestamp")
		msg shouldContain "malformed response"
		msg shouldContain "https://tsa.example.com/timestamp"
		msg shouldContain "verify the timestamp server URL"
	}

	test("buildUserMessage for malformed response without TSA URL omits parentheses") {
		val ex = RuntimeException("malformed timestamp response: bad data")
		val msg = detector.buildUserMessage(ex, null)
		msg shouldContain "malformed response"
		msg shouldContain "verify the timestamp server URL"
	}
})

