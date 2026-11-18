package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.error.TimestampFailureKind
import org.bouncycastle.asn1.cmp.PKIFailureInfo
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Detects TSP (Time-Stamp Protocol) related exceptions thrown by the EU DSS library
 * and extracts human-readable failure reasons from PKIFailureInfo codes.
 *
 * The DSS [eu.europa.esig.dss.service.tsp.OnlineTSPSource] wraps TSA failures in
 * a generic [eu.europa.esig.dss.model.DSSException] whose message contains the
 * string `"timestamp token"` and optionally a `PKIFailureInfo: 0x<hex>` code
 * defined by RFC 3161 § 2.4.2.
 */
class TspErrorDetector {
	
	companion object {
		private val PKI_FAILURE_REGEX = Regex("""PKIFailureInfo:\s*0x([0-9a-fA-F]+)""")
		
		/**
		 * The failures RFC 3161 § 2.4.2 names, in the RFC's order, keyed by the bit each sets in the
		 * code DSS prints.
		 *
		 * DSS prints BouncyCastle's `PKIFailureInfo.toString()`, whose value packs the failure bit
		 * string one byte at a time, its first byte lowest, and the bits of each byte from the most
		 * significant down. RFC bit 0, `badAlg`, therefore reads `0x80`, not `0x1`, and bit 25,
		 * `systemFailure`, reads `0x40000000`. BouncyCastle's own constants hold these values.
		 */
		private val PKI_FAILURE_REASONS: Map<Int, String> = mapOf(
			PKIFailureInfo.badAlg to "badAlg — unrecognized or unsupported algorithm",
			PKIFailureInfo.badRequest to "badRequest — transaction not permitted or supported",
			PKIFailureInfo.badDataFormat to "badDataFormat — submitted data has the wrong format",
			PKIFailureInfo.timeNotAvailable to "timeNotAvailable — TSA's time source is not available",
			PKIFailureInfo.unacceptedPolicy to "unacceptedPolicy — requested TSA policy not supported",
			PKIFailureInfo.unacceptedExtension to "unacceptedExtension — requested extension not supported",
			PKIFailureInfo.addInfoNotAvailable to "addInfoNotAvailable — additional information not available",
			PKIFailureInfo.systemFailure to "systemFailure — internal TSA error",
		)
		
		private val MALFORMED_INDICATORS = listOf(
			"malformed timestamp",
			"invalid tsp response",
			"corrupted stream",
			"failed to construct sequence from byte",
		)

		/**
		 * Wordings `eu.europa.esig.dss.service.tsp.OnlineTSPSource` produces itself, which therefore
		 * attribute a failure to the timestamp request rather than to another part of the extension.
		 *
		 * Two of the [MALFORMED_INDICATORS] — `corrupted stream` and `failed to construct sequence
		 * from byte` — are plain BouncyCastle ASN.1 parse errors that a malformed OCSP or CRL response
		 * raises just as readily. Judging a server unusable on those alone would stop a healthy TSA
		 * from being called because a revocation endpoint returned rubbish.
		 */
		private val TSP_SOURCE_MARKERS = listOf(
			"invalid tsp response",
			"no timestamp token has been retrieved",
			"an error occurred during timestamp request",
		)
	}
	
	/**
	 * Determines whether [exception] (or any of its causes) originates from a TSP / timestamp
	 * failure.
	 *
	 * Detection heuristic: walks the full cause chain and checks whether any message contains
	 * the phrase `"timestamp token"`, `"TSP"` together with failure indicators, or any of
	 * the known malformed-response patterns (e.g. `"Invalid TSP response"`,
	 * `"malformed timestamp"`, `"corrupted stream"`).
	 */
	fun isTspException(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }
			.any { e ->
				val msg = e.message ?: return@any false
				msg.contains("timestamp token", ignoreCase = true) ||
						(msg.contains("TSP", ignoreCase = false) &&
								(msg.contains("Failure", ignoreCase = true) || msg.contains(
									"Status",
									ignoreCase = true
								))) ||
						MALFORMED_INDICATORS.any { indicator -> msg.contains(indicator, ignoreCase = true) }
			}
	
	/**
	 * Determines whether [exception] is a timestamp request to the TSA at [tsaUrl] that failed in a
	 * shape [isTspException] does not recognize, such as an HTTP error status the server answered
	 * with: a refused login or a missing page.
	 *
	 * Either of two things in the cause chain attributes the failure to the timestamp request.
	 *
	 * - A message names [tsaUrl] between square brackets. DSS's `CommonsDataLoader` reports every
	 *   request it could not complete as a `DSSExternalResourceException` reading
	 *   `Unable to process POST call for url [...]. Reason : [...]`, an HTTP error status included,
	 *   whose reason never reaches the protocol layer. It reports a failed OCSP or CRL fetch in the
	 *   same shape, so the URL is what tells the two apart, and only the whole of it: a CRL published
	 *   under the timestamp server's address is not the timestamp server.
	 * - A message carries wording only `eu.europa.esig.dss.service.tsp.OnlineTSPSource` produces,
	 *   such as the `An error occurred during timestamp request` it wraps an I/O failure in.
	 *
	 * A caller that has to tell a failed timestamp request from a revocation failure asks this first,
	 * because both arrive as a `DSSExternalResourceException`.
	 *
	 * @param exception The caught exception (the whole cause chain is inspected).
	 * @param tsaUrl The configured TSA endpoint, or `null` when none could be resolved, in which case
	 *   only the TSP source's wording attributes the failure.
	 * @return `true` when the failure came from the timestamp request.
	 */
	fun isTimestampRequestFailure(exception: Throwable, tsaUrl: String?): Boolean =
		namesTsa(exception, tsaUrl) || isAttributableToTsp(exception)

	/**
	 * Determines whether [exception] means the TSA at [tsaUrl] could not be reached, as opposed to
	 * having answered and refused the request.
	 *
	 * Two conditions must both hold, because DSS reports every failed HTTP call the same way:
	 * `CommonsDataLoader` wraps the transport error in a `DSSExternalResourceException` reading
	 * `Unable to process POST call for url [...]. Reason : [...]`, and produces that same shape for an
	 * OCSP or CRL fetch.
	 *
	 * - The cause chain must contain a transport exception — a timeout, a refused or reset connection,
	 *   an unresolvable host, or a TLS failure — which rules out a server that answered.
	 * - A message must name [tsaUrl] between square brackets, which rules out a revocation endpoint
	 *   failing while the TSA is fine, one under the TSA's own address included.
	 *
	 * [isTspException] does not cover this case, because it looks for TSP wording that a transport
	 * failure never carries: the request never reached the protocol layer.
	 *
	 * @param exception The caught exception (the whole cause chain is inspected).
	 * @param tsaUrl The configured TSA endpoint. Passing `null` leaves the failure unattributable, so
	 *   the result is `false`.
	 * @return `true` when the TSA could not be reached at all.
	 */
	fun isServerUnreachable(exception: Throwable, tsaUrl: String?): Boolean =
		namesTsa(exception, tsaUrl) && generateSequence(exception) { it.cause }.any { e ->
			e is InterruptedIOException ||
					e is SocketException ||
					e is UnknownHostException ||
					e is SSLException
		}

	/**
	 * Whether a message in the [exception] cause chain names [tsaUrl] between the square brackets
	 * DSS's data loader puts around the address of a request it could not complete. Matching the
	 * brackets too keeps a longer address that starts with [tsaUrl], such as a CRL the timestamp
	 * server's host publishes, from passing for it. A `null` or blank [tsaUrl] names nothing.
	 */
	private fun namesTsa(exception: Throwable, tsaUrl: String?): Boolean =
		!tsaUrl.isNullOrBlank() && generateSequence(exception) { it.cause }
			.mapNotNull { it.message }
			.any { it.contains("[$tsaUrl]", ignoreCase = true) }

	/**
	 * Classify how a timestamp request failed.
	 *
	 * The three kinds answer two different questions about one exception. A batch asks whether calling
	 * the server again is worth anything, which
	 * [TimestampFailureKind.isServerWide][cz.pizavo.omnisign.domain.model.error.isServerWide] answers.
	 * An operator asks which problem it was, since [TimestampFailureKind.UNREACHABLE] is an outage to
	 * wait out while [TimestampFailureKind.MALFORMED_RESPONSE] is usually a URL that does not point at
	 * an RFC 3161 endpoint.
	 *
	 * [TimestampFailureKind.MALFORMED_RESPONSE] additionally requires wording from
	 * [TSP_SOURCE_MARKERS], so that an ASN.1 parse failure coming from a revocation response is not
	 * blamed on the TSA. Everything else is [TimestampFailureKind.REJECTED], which is the safe default
	 * because it is the one kind that does not stop a batch.
	 *
	 * @param exception The caught exception (the whole cause chain is inspected).
	 * @param tsaUrl The configured TSA endpoint, used to attribute a transport failure.
	 * @return The kind of failure [exception] represents.
	 */
	fun classify(exception: Throwable, tsaUrl: String?): TimestampFailureKind = when {
		isServerUnreachable(exception, tsaUrl) -> TimestampFailureKind.UNREACHABLE
		isMalformedResponse(exception) && isAttributableToTsp(exception) ->
			TimestampFailureKind.MALFORMED_RESPONSE

		else -> TimestampFailureKind.REJECTED
	}

	/**
	 * Whether the [exception] cause chain carries wording only the DSS TSP source emits, which tells a
	 * generic ASN.1 failure apart from one the timestamp request produced.
	 */
	private fun isAttributableToTsp(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }
			.mapNotNull { it.message }
			.any { msg -> TSP_SOURCE_MARKERS.any { marker -> msg.contains(marker, ignoreCase = true) } }

	/**
	 * Checks whether the [exception] cause chain contains indicators of a malformed
	 * or unparseable TSP response (e.g., the server returned HTML or a truncated byte stream).
	 */
	fun isMalformedResponse(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }
			.any { e ->
				val msg = e.message ?: return@any false
				MALFORMED_INDICATORS.any { indicator -> msg.contains(indicator, ignoreCase = true) }
			}
	
	/**
	 * Builds a user-friendly error message for a TSP failure, including the decoded
	 * PKIFailureInfo reason when present. When the response is malformed (e.g., the TSA
	 * returned HTML instead of an ASN.1 timestamp token), a dedicated hint is produced.
	 *
	 * @param exception The caught exception (cause chain is inspected).
	 * @param tsaUrl The TSA endpoint URL to include in the message for diagnostics.
	 * @return A concise, actionable message suitable for CLI / UI display.
	 */
	fun buildUserMessage(exception: Throwable, tsaUrl: String?): String {
		val tsaPart = tsaUrl?.let { " ($it)" } ?: ""

		if (isServerUnreachable(exception, tsaUrl)) {
			return "Timestamp server$tsaPart could not be reached. " +
					"Check the network connection and that the server is accepting requests."
		}

		if (isMalformedResponse(exception)) {
			return "Timestamp server$tsaPart returned a malformed response. " +
					"The server may be temporarily unavailable, returning an error page, " +
					"or the URL may not be a valid RFC 3161 endpoint. " +
					"Please verify the timestamp server URL and try again."
		}
		
		val reason = parsePkiFailureReason(exception)
		return if (reason != null) {
			"Timestamp server$tsaPart rejected the request: $reason"
		} else {
			"Timestamp server$tsaPart failed to produce a timestamp token"
		}
	}
	
	/**
	 * Decodes the first PKIFailureInfo code in the [exception]'s cause chain into the failures it
	 * names.
	 *
	 * The code is a set of bits, packed as [PKI_FAILURE_REASONS] describes. Every failure RFC 3161
	 * names whose bit is set is described, in the RFC's order and joined by `; `, and the bits it
	 * does not name, such as the further failures of RFC 4210, follow together as an unknown failure
	 * code. A code too long to be one BouncyCastle printed is reported whole as an unknown failure
	 * code.
	 *
	 * @return The description, or `null` when the chain carries no code or the code sets no bit.
	 */
	fun parsePkiFailureReason(exception: Throwable): String? {
		val allMessages = generateSequence(exception) { it.cause }
			.mapNotNull { it.message }
			.joinToString(" ")

		val hex = PKI_FAILURE_REGEX.find(allMessages)?.groupValues?.get(1) ?: return null
		val code = hex.toUIntOrNull(16)?.toInt() ?: return "unknown failure code 0x$hex"
		val named = PKI_FAILURE_REASONS.filterKeys { bit -> (code and bit) != 0 }
		val unnamed = named.keys.fold(code) { rest, bit -> rest and bit.inv() }
		val unknown = if (unnamed == 0) emptyList() else listOf("unknown failure code 0x${unnamed.toUInt().toString(16)}")
		return (named.values + unknown).joinToString("; ").ifEmpty { null }
	}
}


