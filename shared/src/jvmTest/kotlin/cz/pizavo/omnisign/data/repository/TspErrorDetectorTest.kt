package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import cz.pizavo.omnisign.domain.model.error.TimestampFailureKind
import cz.pizavo.omnisign.domain.model.error.isServerWide
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit tests for [TspErrorDetector] verifying TSP exception detection,
 * PKIFailureInfo parsing, reachability classification, and user-friendly message generation.
 *
 * The reachability cases reproduce the wrapper DSS actually raises: `CommonsDataLoader` reports a
 * failed HTTP call as `Unable to process POST call for url [...]. Reason : [...]` with the transport
 * exception as its cause, and uses the same shape for revocation fetches — which is why the URL has
 * to be part of the verdict.
 */
class TspErrorDetectorTest : FunSpec({

	val detector = TspErrorDetector()
	val tsa = "https://tsa.example.com/timestamp"

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

	test("classify calls the TSA unreachable for the wrapper DSS raises when it cannot be dialled") {
		val ex = RuntimeException(
			"Unable to process POST call for url [$tsa]. Reason : [Connect timed out]",
			SocketTimeoutException("Connect timed out"),
		)
		detector.classify(ex, tsa) shouldBe TimestampFailureKind.UNREACHABLE
	}

	test("classify calls a refused connection and an unresolvable host unreachable too") {
		val refused = RuntimeException(
			"Unable to process POST call for url [$tsa]. Reason : [Connection refused]",
			ConnectException("Connection refused"),
		)
		val unknownHost = RuntimeException(
			"Unable to process POST call for url [$tsa]. Reason : [tsa.example.com]",
			UnknownHostException("tsa.example.com"),
		)
		detector.classify(refused, tsa) shouldBe TimestampFailureKind.UNREACHABLE
		detector.classify(unknownHost, tsa) shouldBe TimestampFailureKind.UNREACHABLE
	}

	test("classify separates a server that answered with rubbish from one that could not be reached") {
		val ex = RuntimeException(
			"Invalid TSP response : malformed timestamp response: " +
				"java.lang.IllegalArgumentException: failed to construct sequence from byte[]: " +
				"corrupted stream - out of bounds length found: 108 >= 18"
		)
		detector.classify(ex, tsa) shouldBe TimestampFailureKind.MALFORMED_RESPONSE
		detector.classify(ex, tsa).isServerWide shouldBe true
	}

	test("classify calls a PKIFailureInfo refusal rejected, which does not stop a batch") {
		val ex = RuntimeException("No timestamp token has been retrieved (PKIFailureInfo: 0x4)")
		detector.isTspException(ex) shouldBe true
		detector.classify(ex, tsa) shouldBe TimestampFailureKind.REJECTED
		detector.classify(ex, tsa).isServerWide shouldBe false
	}

	test("classify does not blame the TSA when a different endpoint is the one that timed out") {
		val ex = RuntimeException(
			"Unable to process GET call for url [http://ocsp.example.com]. Reason : [Read timed out]",
			SocketTimeoutException("Read timed out"),
		)
		detector.classify(ex, tsa).isServerWide shouldBe false
	}

	test("classify cannot attribute a transport failure without a configured TSA URL") {
		val ex = RuntimeException("Unable to process POST call", SocketTimeoutException("Connect timed out"))
		detector.classify(ex, null).isServerWide shouldBe false
	}

	test("classify does not blame the TSA for an ASN.1 failure with nothing tying it to one") {
		val ex = RuntimeException(
			"failed to construct sequence from byte[]: corrupted stream - out of bounds length found: 108 >= 18"
		)
		detector.isMalformedResponse(ex) shouldBe true
		detector.classify(ex, tsa) shouldBe TimestampFailureKind.REJECTED
	}

	test("buildUserMessage says the server could not be reached for a transport failure") {
		val ex = RuntimeException(
			"Unable to process POST call for url [$tsa]. Reason : [Connect timed out]",
			SocketTimeoutException("Connect timed out"),
		)
		val msg = detector.buildUserMessage(ex, tsa)
		msg shouldContain "could not be reached"
		msg shouldContain tsa
	}
})

