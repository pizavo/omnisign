package cz.pizavo.omnisign.data.repository

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


