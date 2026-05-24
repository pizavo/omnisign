package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * Type of trust service a directly-trusted certificate is trusted for in a given scope.
 *
 * This is a per-reference trust role (a relying-party policy), not an intrinsic property of the
 * certificate: the same anchor may be trusted for different roles in different scopes.
 */
@Serializable
enum class TrustedCertificateType {
	/**
	 * The certificate is trusted for any purpose (both CA and TSA).
	 * Use this for root certificates that can certify both CA and TSA sub-certificates,
	 * or when the specific role is not known or not relevant.
	 */
	ANY,

	/**
	 * Certificate Authority — the certificate is trusted as a CA root or intermediate.
	 */
	CA,

	/**
	 * Time Stamping Authority — the certificate is trusted as a TSA signer.
	 */
	TSA
}
