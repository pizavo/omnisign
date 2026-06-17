package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.spi.exception.DSSExternalResourceException

/**
 * Detects revocation-data failures thrown by the EU DSS library while extending a signature to
 * B-LT / B-LTA, when DSS must fetch and embed CRL/OCSP revocation data.
 *
 * Detection is typed-first: any [DSSExternalResourceException] in the cause chain — DSS's
 * external-resource fetch failure, including the `DSSDataLoaderMultipleException` subclass thrown
 * when every CRL/OCSP endpoint fails — is a reliable, locale-independent signal.
 *
 * A keyword fallback on the exception message is retained deliberately: DSS's OCSP source and
 * `PAdESService` can surface a revocation failure as a bare `DSSException`, which has no
 * revocation-specific subtype to match on (matching the base type would swallow every unrelated
 * extension failure into a revocation error). The fallback is the only way to recognise those, at
 * the cost of being locale- and version-sensitive.
 */
class RevocationErrorDetector {

	/**
	 * Whether [exception] — or any exception in its cause chain — is a revocation-data failure.
	 *
	 * Returns `true` when the chain contains a [DSSExternalResourceException] (the typed,
	 * authoritative signal), or, as a fallback for failures surfaced as a bare DSS exception, when
	 * any message mentions revocation, OCSP, or CRL.
	 */
	fun isRevocationException(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }.any { e ->
			e is DSSExternalResourceException ||
					(e.message?.let {
						it.contains("revocation", ignoreCase = true) ||
								it.contains("OCSP", ignoreCase = true) ||
								it.contains("CRL", ignoreCase = true)
					} ?: false)
		}
}
