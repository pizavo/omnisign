package cz.pizavo.omnisign.data.repository

/**
 * Where [DssServiceFactory]'s verifiers may download a certificate's issuer from, when they follow
 * the URL in the certificate's Authority Information Access extension; see [AiaDataLoader].
 *
 * `jvmRepositoryModule` binds it with private addresses allowed, for the desktop app and the CLI:
 * their user processes their own documents, and an issuer may be published on an internal network.
 * The server overrides the binding from the `aia` block of its `server.yml` and blocks them unless
 * told otherwise, because it processes documents from anyone who can reach it, and the URLs in
 * those documents would otherwise let the sender make the server request internal addresses.
 *
 * @property allowPrivateAddresses Whether downloads may reach loopback, private-network, link-local
 *   and other non-public addresses.
 */
data class AiaFetchPolicy(val allowPrivateAddresses: Boolean)
