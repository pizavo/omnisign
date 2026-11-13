package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.detailedreport.DetailedReport
import eu.europa.esig.dss.enumerations.Indication

/**
 * Ids of the revocation tokens DSS accepted as evidence about the certificate [certificateId] when
 * it validated the token [tokenId]: those whose revocation acceptance check (DSS's `RAC` block)
 * passed in the X.509 certificate validation of [tokenId]'s basic building blocks.
 *
 * DSS runs that check on every revocation token it holds for a certificate whose revocation status
 * it needs. Among other things it verifies the token's signature, that the token's issuer is known
 * and chains to a trust anchor, that the token gives a known status, and that it covers the
 * certificate. A token that failed, or that was never checked because the certificate's chain
 * reaches no trust anchor or the certificate needs no revocation data, is not in the set.
 */
internal fun DetailedReport.acceptedRevocationIds(tokenId: String, certificateId: String): Set<String> =
	getBasicBuildingBlockById(tokenId)?.xcv?.subXCV
		?.firstOrNull { it.id == certificateId }
		?.crs?.rac
		?.filter { it.conclusion?.indication == Indication.PASSED }
		?.mapTo(mutableSetOf()) { it.id }
		?: emptySet()
