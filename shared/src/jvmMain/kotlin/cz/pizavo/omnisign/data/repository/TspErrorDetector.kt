package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.error.TimestampFailureKind
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
		
		private val PKI_FAILURE_REASONS: Map<Int, String> = mapOf(
			0x01 to "badAlg — unrecognized or unsupported algorithm",
			0x04 to "badRequest — transaction not permitted or supported",
			0x20 to "badDataFormat — submitted data has the wrong format",
			0x4000 to "timeNotAvailable — TSA's time source is not available",
			0x8000 to "unacceptedPolicy — requested TSA policy not supported",
			0x10000 to "unacceptedExtension — requested extension not supported",
			0x20000 to "addInfoNotAvailable — additional information not available",
			0x2000000 to "systemFailure — internal TSA error",
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
	 * - The joined messages must name [tsaUrl], which rules out a revocation endpoint failing while
	 *   the TSA is fine.
	 *
	 * [isTspException] does not cover this case, because it looks for TSP wording that a transport
	 * failure never carries: the request never reached the protocol layer.
	 *
	 * @param exception The caught exception (the whole cause chain is inspected).
	 * @param tsaUrl The configured TSA endpoint. Passing `null` leaves the failure unattributable, so
	 *   the result is `false`.
	 * @return `true` when the TSA could not be reached at all.
	 */
	fun isServerUnreachable(exception: Throwable, tsaUrl: String?): Boolean {
		if (tsaUrl.isNullOrBlank()) return false
		val chain = generateSequence(exception) { it.cause }.toList()
		val transport = chain.any { e ->
			e is InterruptedIOException ||
					e is SocketException ||
					e is UnknownHostException ||
					e is SSLException
		}
		if (!transport) return false
		return chain.mapNotNull { it.message }.any { it.contains(tsaUrl, ignoreCase = true) }
	}

	/**
	 * Classify how a timestamp request failed.
	 *
	 * The three kinds answer two different questions about one exception. A batch asks whether calling
	 * the server again is worth anything, which [TimestampFailureKind.isServerWide] answers. An
	 * operator asks which problem it was, since [TimestampFailureKind.UNREACHABLE] is an outage to
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
	 * Extracts the first PKIFailureInfo hex code from the [exception]'s cause chain
	 * and returns its human-readable description, or `null` when no code is found.
	 */
	fun parsePkiFailureReason(exception: Throwable): String? {
		val allMessages = generateSequence(exception) { it.cause }
			.mapNotNull { it.message }
			.joinToString(" ")
		
		val match = PKI_FAILURE_REGEX.find(allMessages) ?: return null
		val code = match.groupValues[1].toIntOrNull(16) ?: return "unknown failure code 0x${match.groupValues[1]}"
		return PKI_FAILURE_REASONS[code] ?: "unknown failure code 0x${match.groupValues[1]}"
	}
}


