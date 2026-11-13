package cz.pizavo.omnisign.config

/**
 * Where the server may download a certificate's issuer from, the `aia:` block of `server.yml`.
 *
 * When DSS cannot find the issuer of a certificate it validates, in the document or among the
 * trusted certificates, it downloads the issuer from the URL in the certificate's Authority
 * Information Access (AIA) extension. Those URLs come from the documents sent to the server, so
 * from anyone who can reach it, and DSS follows them before it knows whether a chain is trusted.
 * Whatever this block says, the downloads use only `http` and `https`, have a time limit and a size
 * cap.
 *
 * @property allowPrivateAddresses Whether those downloads may reach loopback, private-network,
 *   link-local and other non-public addresses. Defaults to `false`, so a document cannot make the
 *   server request internal services such as a cloud metadata endpoint. Set it to `true` only when
 *   an internal CA publishes its issuer certificates on an internal address and the documents the
 *   server processes do not embed them.
 */
data class AiaConfig(
	val allowPrivateAddresses: Boolean = false,
)
