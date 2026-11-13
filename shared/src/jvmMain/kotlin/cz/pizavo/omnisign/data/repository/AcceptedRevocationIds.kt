package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.detailedreport.DetailedReport
import eu.europa.esig.dss.diagnostic.DiagnosticData
import eu.europa.esig.dss.enumerations.Indication
import eu.europa.esig.dss.i18n.I18nProvider
import eu.europa.esig.dss.validation.process.ValidationProcessUtils
import eu.europa.esig.dss.validation.process.vpfltvd.RevocationBasicValidationProcess
import java.util.Locale

/**
 * Ids of the revocation tokens DSS accepted as evidence about the certificate [certificateId] when
 * it validated the token [tokenId]. A revocation token counts only when it passes both checks that
 * DSS's long-term validation makes before it lets a token speak for a certificate.
 *
 * The first is the token's revocation acceptance check (DSS's `RAC` block) in the X.509 certificate
 * validation of [tokenId]'s basic building blocks. DSS runs it on every revocation token it holds for
 * a certificate whose revocation status it needs. Among other things it verifies the token's
 * signature, that the token's issuer is known and chains to a trust anchor, that the token gives a
 * known status, and that it covers the certificate.
 *
 * The second is the token's own basic validation, as DSS's `RevocationBasicValidationProcess`
 * concludes it from the token's basic building blocks in [diagnosticData]. It also holds the
 * certificates that issued the token to the policy's constraints, such as being a CA within its path
 * length, which the acceptance check leaves out. It must come out PASSED, or INDETERMINATE for a
 * reason that a proof of existence may still resolve. DSS validates revocation tokens on their own
 * only from the long-term level up, so a token without basic building blocks is judged by the
 * acceptance check alone, as DSS judges it.
 *
 * A token that fails either check, or that was never checked because the certificate's chain reaches
 * no trust anchor or the certificate needs no revocation data, is not in the set.
 */
internal fun DetailedReport.acceptedRevocationIds(
	diagnosticData: DiagnosticData,
	tokenId: String,
	certificateId: String,
): Set<String> {
	val passedAcceptance = getBasicBuildingBlockById(tokenId)?.xcv?.subXCV
		?.firstOrNull { it.id == certificateId }
		?.crs?.rac
		?.filter { it.conclusion?.indication == Indication.PASSED }
		?.map { it.id }
		.orEmpty()
	if (passedAcceptance.isEmpty()) return emptySet()
	
	val buildingBlocks = jaxbModel.basicBuildingBlocks.associateBy { it.id }
	return passedAcceptance.filterTo(mutableSetOf()) { revocationId ->
		if (revocationId !in buildingBlocks) return@filterTo true
		val revocation = diagnosticData.getRevocationById(revocationId) ?: return@filterTo false
		val conclusion = RevocationBasicValidationProcess(
			I18nProvider(Locale.ENGLISH),
			diagnosticData,
			revocation,
			buildingBlocks,
		).execute().conclusion
		ValidationProcessUtils.isAllowedBasicRevocationDataValidation(conclusion)
	}
}
