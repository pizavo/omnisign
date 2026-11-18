package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.client.http.DataLoader
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import cz.pizavo.omnisign.domain.model.error.TimestampFailureKind
import cz.pizavo.omnisign.domain.model.error.isServerWide
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.cmp.PKIFailureInfo
import org.bouncycastle.asn1.cmp.PKIFreeText
import org.bouncycastle.asn1.cmp.PKIStatus
import org.bouncycastle.asn1.cmp.PKIStatusInfo
import org.bouncycastle.asn1.tsp.TimeStampResp
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit tests for [TspErrorDetector] verifying TSP exception detection,
 * PKIFailureInfo parsing, reachability classification, the attribution of a failed request to the
 * timestamp server, and user-friendly message generation.
 *
 * The reachability and attribution cases reproduce the wrapper DSS actually raises:
 * `CommonsDataLoader` reports a failed HTTP call as `Unable to process POST call for url [...]. Reason : [...]`
 * with the transport exception, or the HTTP error status it would not accept, as its cause, and uses
 * the same shape for revocation fetches — which is why the URL has to be part of the verdict, and
 * the whole URL between its brackets, since a CRL can live under the timestamp server's address.
 *
 * The refusals are built as a TSA sends them, from RFC 3161's bit numbers, and passed through DSS's
 * own `OnlineTSPSource`, so the code each carries is the one DSS prints, whatever table the detector
 * decodes it with.
 */
class TspErrorDetectorTest : FunSpec({

	val detector = TspErrorDetector()
	val tsa = "https://tsa.example.com/timestamp"

	/**
	 * The answer a TSA sends when it refuses a request: a `TimeStampResp` whose status is a rejection
	 * and whose failure info sets the RFC 3161 bits [bits], laid out as DER lays out a named bit
	 * string, bit 0 being the most significant bit of the first byte.
	 */
	fun rejection(vararg bits: Int): ByteArray {
		val last = bits.max()
		val octets = ByteArray(last / 8 + 1)
		for (bit in bits) octets[bit / 8] = (octets[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
		val failInfo = PKIFailureInfo(DERBitString(octets, 7 - last % 8))
		return TimeStampResp(PKIStatusInfo(PKIStatus.rejection, PKIFreeText("Request refused"), failInfo), null).encoded
	}

	/**
	 * The exception DSS's `OnlineTSPSource` raises when the TSA at [tsa] answers its request with the
	 * [rejection] that sets [bits].
	 */
	fun refusal(vararg bits: Int): DSSExternalResourceException {
		val loader = mockk<DataLoader>(relaxed = true)
		every { loader.post(tsa, any()) } returns rejection(*bits)
		return shouldThrow<DSSExternalResourceException> {
			OnlineTSPSource(tsa, loader).getTimeStampResponse(DigestAlgorithm.SHA256, ByteArray(32))
		}
	}

	val rfc3161Failures = listOf(
		0 to "badAlg — unrecognized or unsupported algorithm",
		2 to "badRequest — transaction not permitted or supported",
		5 to "badDataFormat — submitted data has the wrong format",
		14 to "timeNotAvailable — TSA's time source is not available",
		15 to "unacceptedPolicy — requested TSA policy not supported",
		16 to "unacceptedExtension — requested extension not supported",
		17 to "addInfoNotAvailable — additional information not available",
		25 to "systemFailure — internal TSA error",
	)

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

	test("DSS prints the failure info as BouncyCastle packs it, RFC 3161's bit 0 as 0x80 and bit 25 as 0x40000000") {
		refusal(0).message shouldContain "PKIFailureInfo: 0x80)"
		refusal(25).message shouldContain "PKIFailureInfo: 0x40000000)"
	}

	for ((bit, reason) in rfc3161Failures) {
		test("a TSA's refusal with RFC 3161 failure bit $bit is reported as ${reason.substringBefore(' ')}") {
			detector.buildUserMessage(refusal(bit), tsa) shouldBe "Timestamp server ($tsa) rejected the request: $reason"
		}
	}

	test("a refusal with two RFC 3161 failure bits names both, in the RFC's order") {
		detector.parsePkiFailureReason(refusal(15, 0)) shouldBe
				"badAlg — unrecognized or unsupported algorithm; unacceptedPolicy — requested TSA policy not supported"
	}

	test("a failure bit RFC 3161 does not name is reported as an unknown failure code") {
		detector.parsePkiFailureReason(refusal(1)) shouldBe "unknown failure code 0x40"
	}

	test("the failure bits RFC 3161 does not name follow the named ones together, as one unknown failure code") {
		detector.parsePkiFailureReason(refusal(0, 1, 23)) shouldBe
				"badAlg — unrecognized or unsupported algorithm; unknown failure code 0x10040"
	}

	test("a code with the highest bit BouncyCastle packs into it is decoded, that bit not read as a sign") {
		detector.parsePkiFailureReason(refusal(24, 25)) shouldBe
				"systemFailure — internal TSA error; unknown failure code 0x80000000"
	}

	test("a failure info that sets no bit names no failure") {
		val ex = RuntimeException("No timestamp token has been retrieved (TSP Status : Request refused / PKIFailureInfo: 0x0)")
		detector.parsePkiFailureReason(ex).shouldBeNull()
		detector.buildUserMessage(ex, tsa) shouldBe "Timestamp server ($tsa) failed to produce a timestamp token"
	}

	test("a code too long to be one BouncyCastle printed is reported whole as an unknown failure code") {
		detector.parsePkiFailureReason(RuntimeException("PKIFailureInfo: 0x123456789")) shouldBe
				"unknown failure code 0x123456789"
	}

	test("parsePkiFailureReason returns null when no PKIFailureInfo is present") {
		val ex = RuntimeException("No timestamp token has been retrieved")
		detector.parsePkiFailureReason(ex).shouldBeNull()
	}

	test("parsePkiFailureReason finds the code in a cause the refusal was wrapped in") {
		val wrapper = RuntimeException("Signing failed", refusal(2))
		detector.parsePkiFailureReason(wrapper) shouldBe "badRequest — transaction not permitted or supported"
	}

	test("buildUserMessage without TSA URL omits parentheses") {
		detector.buildUserMessage(refusal(2), null) shouldBe
				"Timestamp server rejected the request: badRequest — transaction not permitted or supported"
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

	test("a real TSA's refusal of a request it could not decode reads as badDataFormat") {
		val ex = RuntimeException(
			"No timestamp token has been retrieved (TSP Status : " +
				"TimeStampReq: Asn1Exception: ASN.1 decode error @ offset 0:" +
				"Unexpected end-of-buffer encountered. / PKIFailureInfo: 0x4)"
		)
		detector.parsePkiFailureReason(ex) shouldBe "badDataFormat — submitted data has the wrong format"
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

	test("classify does not blame the TSA when a CRL under its own address is the one that timed out") {
		val hostTsa = "http://pki.example.com"
		val ex = RuntimeException(
			"Unable to process GET call for url [$hostTsa/ca.crl]. Reason : [Read timed out]",
			SocketTimeoutException("Read timed out"),
		)
		detector.isServerUnreachable(ex, hostTsa) shouldBe false
		detector.classify(ex, hostTsa).isServerWide shouldBe false
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

	val unauthorized = "Not acceptable HTTP Status (HTTP status code : 401 / reason : Unauthorized)"

	test("isTimestampRequestFailure attributes an HTTP error status from the TSA to the timestamp request") {
		val ex = RuntimeException(
			"Unable to process POST call for url [$tsa]. Reason : [$unauthorized]",
			IOException(unauthorized),
		)
		detector.isTspException(ex) shouldBe false
		detector.isServerUnreachable(ex, tsa) shouldBe false
		detector.isTimestampRequestFailure(ex, tsa) shouldBe true
	}

	test("isTimestampRequestFailure finds the TSA's URL in a cause the failure was wrapped in") {
		val ex = RuntimeException(
			"Extension failed",
			RuntimeException("Unable to process POST call for url [$tsa]. Reason : [$unauthorized]", IOException(unauthorized)),
		)
		detector.isTimestampRequestFailure(ex, tsa) shouldBe true
	}

	test("isTimestampRequestFailure finds the TSP source's wording in a cause the failure was wrapped in") {
		val ex = RuntimeException(
			"Extension failed",
			RuntimeException("An error occurred during timestamp request : unexpected end-of-contents marker"),
		)
		detector.isTimestampRequestFailure(ex, null) shouldBe true
	}

	test("isTimestampRequestFailure does not blame the TSA for an HTTP error status from another endpoint") {
		val ex = RuntimeException(
			"Unable to process POST call for url [http://ocsp.example.com]. Reason : [$unauthorized]",
			IOException(unauthorized),
		)
		detector.isTimestampRequestFailure(ex, tsa) shouldBe false
	}

	test("isTimestampRequestFailure does not take a CRL under the TSA's own address for the TSA") {
		val hostTsa = "http://pki.example.com"
		val crlFailure = RuntimeException(
			"Unable to process GET call for url [$hostTsa/ca.crl]. Reason : [$unauthorized]",
			IOException(unauthorized),
		)
		val tsaFailure = RuntimeException(
			"Unable to process POST call for url [$hostTsa]. Reason : [$unauthorized]",
			IOException(unauthorized),
		)
		detector.isTimestampRequestFailure(crlFailure, hostTsa) shouldBe false
		detector.isTimestampRequestFailure(tsaFailure, hostTsa) shouldBe true
	}

	test("an address that only ends with the TSA's URL is not the TSA, whether it refuses or times out") {
		val proxied = "http://proxy.example.net/fetch?url=$tsa"
		val refused = RuntimeException(
			"Unable to process GET call for url [$proxied]. Reason : [$unauthorized]",
			IOException(unauthorized),
		)
		val timedOut = RuntimeException(
			"Unable to process GET call for url [$proxied]. Reason : [Read timed out]",
			SocketTimeoutException("Read timed out"),
		)
		detector.isTimestampRequestFailure(refused, tsa) shouldBe false
		detector.isServerUnreachable(timedOut, tsa) shouldBe false
		detector.classify(timedOut, tsa).isServerWide shouldBe false
	}

	test("isTimestampRequestFailure attributes nothing to a missing or blank TSA URL") {
		val ex = RuntimeException("Unable to process POST call for url [$tsa]. Reason : [$unauthorized]")
		detector.isTimestampRequestFailure(ex, null) shouldBe false
		detector.isTimestampRequestFailure(ex, "") shouldBe false
		detector.isTimestampRequestFailure(ex, " ") shouldBe false
	}

	test("isTimestampRequestFailure recognizes the wrapper the TSP source puts around an I/O failure") {
		val ex = RuntimeException(
			"An error occurred during timestamp request : unexpected end-of-contents marker",
			IOException("unexpected end-of-contents marker"),
		)
		detector.isTspException(ex) shouldBe false
		detector.isTimestampRequestFailure(ex, null) shouldBe true
	}
})

