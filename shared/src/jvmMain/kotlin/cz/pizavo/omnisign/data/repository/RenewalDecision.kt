package cz.pizavo.omnisign.data.repository

/**
 * Outcome of the coverage-aware archival-renewal check for a single PAdES document.
 *
 * Three-valued on purpose: a renewal-relevant timestamp whose signing (TSA) certificate cannot be
 * resolved leaves its expiry unknown, which is neither "needs renewal" nor "safe". It is reported
 * as [UNDETERMINABLE] so the caller can surface it rather than silently treat the document as
 * not needing renewal.
 */
internal enum class RenewalDecision {
	/** At least one uncovered, renewal-relevant timestamp expires within the renewal window. */
	NEEDED,

	/** Every uncovered, renewal-relevant timestamp has a resolvable certificate that outlasts the window. */
	NOT_NEEDED,

	/**
	 * No timestamp clearly needs renewal, yet at least one uncovered, renewal-relevant timestamp has
	 * an unresolvable signing certificate, so its expiry — and thus the renewal decision — is unknown.
	 */
	UNDETERMINABLE,
}
