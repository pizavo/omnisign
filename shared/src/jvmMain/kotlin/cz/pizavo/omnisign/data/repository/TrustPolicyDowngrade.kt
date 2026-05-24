package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType

/**
 * Decide whether a validated component (signature or timestamp) is distrusted by the per-reference
 * trust policy.
 *
 * A component is downgraded when its certificate chain terminates *only* at store-managed trust
 * anchors typed for the wrong role: there is at least one managed anchor in the chain
 * ([managedTypes] is non-empty) but none of them grants [requiredRole] or
 * [TrustedCertificateType.ANY]. When [managedTypes] is empty, trust came from a trusted list or an
 * unmanaged anchor and is never downgraded.
 *
 * @param managedTypes Per-reference types of the store-managed anchors that terminate the chain.
 * @param requiredRole The role the component needs - [TrustedCertificateType.CA] for a signature,
 *   [TrustedCertificateType.TSA] for a timestamp.
 * @return True when the component should be marked policy-untrusted.
 */
internal fun isDowngradedByPolicy(
	managedTypes: List<TrustedCertificateType>,
	requiredRole: TrustedCertificateType,
): Boolean = managedTypes.isNotEmpty() &&
	managedTypes.none { it == requiredRole || it == TrustedCertificateType.ANY }
