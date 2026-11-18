package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.spi.validation.CertificateVerifier
import eu.europa.esig.dss.spi.validation.CertificateVerifierBuilder
import eu.europa.esig.dss.spi.validation.SignatureValidationAlerter
import eu.europa.esig.dss.spi.validation.SignatureValidationContext
import eu.europa.esig.dss.spi.validation.status.TokenStatus
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken

/**
 * The revocation data that shows the certificate [signatureParameters] sign with, or a certificate
 * above it in its chain, revoked or suspended at their signing time, or null when there is none.
 *
 * DSS establishes the status, through a complete copy of [verifier], so with its revocation
 * sources, trust anchors and AIA source. It fetches revocation data for the chain and walks it from
 * the signing certificate up to the first certificate that is trusted or self-signed, and stops at
 * the first certificate whose revocation data is not *good* at the signing time. That certificate's
 * revocation data is what this returns, and only when the data says *revoked*, which a suspension
 * also is: a status that is neither good nor revoked, such as an OCSP response of *unknown*, is not
 * a revocation, so it returns null, without looking at the certificates above. It also returns null
 * when no revocation data can be obtained, and when the chain leads to no trusted certificate, as
 * OmniSign's verifiers fetch revocation data for trusted chains only.
 *
 * DSS runs the same check before it signs when the signature parameters set
 * `isCheckCertificateRevocation`, and reports a revoked certificate through the verifier's
 * revoked-certificate alert. OmniSign does not set it, because the signing verifier sends that
 * alert to a [CollectingStatusAlert]: at B-LT and B-LTA DSS raises it again while it gathers the
 * validation data, for every signature in the document, and a co-signer's revoked certificate is
 * not a reason to refuse to sign. Here the copy's revoked-certificate alert records the certificate
 * instead, so that the caller can refuse to sign with it.
 */
internal fun signingChainRevocation(
	signatureParameters: PAdESSignatureParameters,
	verifier: CertificateVerifier,
): RevocationToken<*>? {
	val signingCertificate = signatureParameters.signingCertificate
	var notGood: CertificateToken? = null
	val recordingVerifier = CertificateVerifierBuilder(verifier).buildCompleteCopy().apply {
		alertOnRevokedCertificate = StatusAlert { status ->
			notGood = (status as? TokenStatus)?.relatedTokens?.filterIsInstance<CertificateToken>()?.firstOrNull()
		}
	}
	val context = SignatureValidationContext(signatureParameters.bLevel().signingDate).apply {
		initialize(recordingVerifier)
		addCertificateTokenForVerification(signingCertificate)
		signatureParameters.certificateChain.forEach { addCertificateTokenForVerification(it) }
		validate()
	}
	SignatureValidationAlerter(context).assertCertificateNotRevoked(signingCertificate)
	val certificate = notGood ?: return null
	return context.getRevocationData(certificate).firstOrNull { it.status.isRevoked }
}
