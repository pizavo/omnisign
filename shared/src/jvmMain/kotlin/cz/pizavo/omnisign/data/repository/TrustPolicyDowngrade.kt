package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import eu.europa.esig.dss.diagnostic.CertificateWrapper
import eu.europa.esig.dss.enumerations.CertificateSourceType

/**
 * Decide whether a validated component (signature or timestamp) is distrusted by the per-reference
 * trust policy.
 *
 * A component is downgraded when the trust anchor its chain ends at is managed by the trust store
 * and typed for the other role: [anchorType] is set, and it grants neither [requiredRole] nor
 * [TrustedCertificateType.ANY]. When [anchorType] is `null`, trust came from a trusted list or an
 * anchor the store does not manage, and it is never downgraded.
 *
 * @param anchorType The per-reference type of the store-managed anchor the component's chain ends
 *   at, as [managedAnchorType] finds it, or `null` when the store does not decide the trust.
 * @param requiredRole The role the component needs - [TrustedCertificateType.CA] for a signature,
 *   [TrustedCertificateType.TSA] for a timestamp.
 * @return True when the component should be marked policy-untrusted.
 */
internal fun isDowngradedByPolicy(
	anchorType: TrustedCertificateType?,
	requiredRole: TrustedCertificateType,
): Boolean = anchorType != null && anchorType != requiredRole && anchorType != TrustedCertificateType.ANY

/**
 * The per-reference type of the store-managed trust anchor [chain] ends at: the first trusted
 * certificate in it, signing certificate first. DSS lists a chain up to its self-signed root, past
 * any certificate it trusts, but it validates the chain only up to the first trusted one, so the
 * certificates above that anchor take no part in the component's trust, whatever the store says of
 * them.
 *
 * `null` when no certificate in [chain] is trusted, when the store does not manage the anchor, and
 * when a trusted list trusts the anchor too, since trust a trusted list gives is never downgraded.
 *
 * @param chain The component's certificate chain as DSS reports it, signing certificate first.
 * @param anchorTypes DSS certificate id → per-reference trust type of each store-managed anchor.
 * @return The anchor's type, or `null` when the store does not decide the component's trust.
 */
internal fun managedAnchorType(
	chain: List<CertificateWrapper>,
	anchorTypes: Map<String, TrustedCertificateType>,
): TrustedCertificateType? =
	chain.firstOrNull { it.isTrusted }
		?.takeUnless { CertificateSourceType.TRUSTED_LIST in it.sources }
		?.let { anchorTypes[it.id] }
