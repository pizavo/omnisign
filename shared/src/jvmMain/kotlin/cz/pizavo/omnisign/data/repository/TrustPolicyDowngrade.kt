package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import eu.europa.esig.dss.detailedreport.jaxb.XmlXCV
import eu.europa.esig.dss.diagnostic.CertificateWrapper
import eu.europa.esig.dss.enumerations.CertificateSourceType
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.spi.x509.CertificateSource
import java.util.Date

/**
 * Decide whether a validated component (signature or timestamp) is distrusted by the per-reference
 * trust policy.
 *
 * A component is downgraded when the trust anchor DSS validated its chain against is managed by the
 * trust store and typed for the other role: [anchorType] is set, and it grants neither
 * [requiredRole] nor [TrustedCertificateType.ANY]. When [anchorType] is `null`, trust came from a
 * trusted list or an anchor the store does not manage, and it is never downgraded.
 *
 * @param anchorType The per-reference type of the store-managed anchor DSS validated the
 *   component's chain against, as [managedAnchorType] finds it, or `null` when the store does not
 *   decide the trust.
 * @param requiredRole The role the component needs - [TrustedCertificateType.CA] for a signature,
 *   [TrustedCertificateType.TSA] for a timestamp.
 * @return True when the component should be marked policy-untrusted.
 */
internal fun isDowngradedByPolicy(
	anchorType: TrustedCertificateType?,
	requiredRole: TrustedCertificateType,
): Boolean = anchorType != null && anchorType != requiredRole && anchorType != TrustedCertificateType.ANY

/**
 * The per-reference type of the store-managed trust anchor DSS validated [chain] against: the last
 * trusted certificate that [xcv], DSS's X.509 certificate validation of the component, checked.
 *
 * DSS checks the certificates of a chain in turn, signing certificate first, and stops at the first
 * trusted one it can take for an anchor: one it still trusts at the validation time, unless the
 * policy lets an ended trust count, or else the topmost trusted certificate. A trusted certificate
 * it passed over, such as a CA a trusted list stopped trusting before the validation time, is
 * therefore not the anchor, and neither are the trusted certificates above the anchor, which DSS
 * lists up to the self-signed root.
 *
 * A trust ends only where a source records trust periods. The trust store records none, and DSS's
 * trusted-list job records them only for a list that sets a trust-anchor validity predicate, which
 * OmniSign's lists do not, so today the anchor is always the first trusted certificate in [chain].
 * Reading it from [xcv] keeps to DSS's choice should a list record trust periods, whatever the
 * policy says of ended trust.
 *
 * `null` when DSS reached no anchor, when the store does not manage the anchor, and when a trusted
 * list trusts the anchor at the validation time too, since trust a trusted list gives is never
 * downgraded.
 *
 * @param chain The component's certificate chain as DSS reports it, signing certificate first.
 * @param xcv DSS's X.509 certificate validation of the component, from its basic building blocks in
 *   the detailed report, or `null` when DSS ran none.
 * @param anchorTypes DSS certificate id → per-reference trust type of each store-managed anchor.
 * @param trustedByList Whether a trusted list trusts a certificate at the validation time, as
 *   [isTrustedByListAt] decides it.
 * @return The anchor's type, or `null` when the store does not decide the component's trust.
 */
internal fun managedAnchorType(
	chain: List<CertificateWrapper>,
	xcv: XmlXCV?,
	anchorTypes: Map<String, TrustedCertificateType>,
	trustedByList: (CertificateWrapper) -> Boolean,
): TrustedCertificateType? {
	val anchorId = xcv?.subXCV?.lastOrNull { it.isTrustAnchor == true }?.id ?: return null
	val anchor = chain.firstOrNull { it.id == anchorId } ?: return null
	val type = anchorTypes[anchor.id] ?: return null
	return type.takeUnless { trustedByList(anchor) }
}

/**
 * Whether one of [sources] that is a trusted list trusts [certificate] at [time]. A trusted list
 * that sets a trust-anchor validity predicate trusts a certificate for the periods in which its
 * trust service had a status the predicate accepts, such as granted, so such a list naming a
 * certificate whose service it withdrew before [time] does not count. A list without one, as
 * OmniSign's lists are, trusts every certificate it names for good. Sources other than trusted
 * lists, the trust store among them, never count.
 *
 * @param sources The trusted certificate sources the validation ran with.
 * @param certificate The certificate to look up.
 * @param time The validation time.
 * @return True when a trusted list trusts [certificate] at [time].
 */
internal fun isTrustedByListAt(sources: List<CertificateSource>, certificate: CertificateToken, time: Date): Boolean =
	sources.any { it.certificateSourceType == CertificateSourceType.TRUSTED_LIST && it.isTrustedAtTime(certificate, time) }
