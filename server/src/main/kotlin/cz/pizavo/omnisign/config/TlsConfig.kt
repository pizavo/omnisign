package cz.pizavo.omnisign.config

/**
 * TLS keystore configuration.
 *
 * When present and reverse-proxy mode is inactive ([ServerConfig.proxy] absent or
 * `proxy.enabled: false`), the server creates a TLS connector that offers the protocol versions in
 * [enabledProtocols], TLS 1.3 and TLS 1.2 unless the list says otherwise, with HTTP/2 ALPN
 * negotiation.
 *
 * The keystore and private-key passwords are deliberately NOT fields on this class.
 * They are resolved at server startup from the environment variables
 * `OMNISIGN_TLS_KEYSTORE_PASSWORD` and `OMNISIGN_TLS_PRIVATE_KEY_PASSWORD`
 * (the second optional, falling back to the first). See [ServerSecrets] for the
 * rationale — YAML-stored secrets cross filesystem boundaries that env vars do not,
 * and the shipped `"changeit"` placeholder is a well-known copy-paste trap.
 *
 * @property port Port the TLS connector listens on. Defaults to `18443`. Lives under
 *   `tls:` (rather than at the root) so it is only meaningful when TLS is configured,
 *   matching the nesting pattern of [ProxyConfig] / [CorsConfig] / [AuthConfig].
 * @property keystorePath Absolute path to the JKS or PKCS#12 keystore file.
 * @property keyAlias Alias of the private key entry inside the keystore.
 * @property hsts HTTP Strict Transport Security configuration. When non-null, the
 *   `Strict-Transport-Security` header is sent on every response. Nesting it here ensures
 *   HSTS is automatically disabled whenever the `tls:` block is removed.
 * @property enabledProtocols The TLS protocol versions the connector offers: both of
 *   [SUPPORTED_PROTOCOLS] by default, and `[TLSv1.3]` for TLS 1.3 alone. The connector sets them on
 *   the SSL engine of every connection, so a client that offers none of them is refused during the
 *   handshake. They narrow what the JVM allows and never widen it: a version that the JVM's
 *   `jdk.tls.disabledAlgorithms` security property disables stays off. An empty list, or a version
 *   other than these two, fails startup, as [validateTlsConfig] describes.
 */
data class TlsConfig(
	val keystorePath: String,
	val keyAlias: String = "omnisign",
	val port: Int = 18443,
	val hsts: HstsConfig? = null,
	val enabledProtocols: List<String> = SUPPORTED_PROTOCOLS,
) {
	/**
	 * The protocol versions a TLS connector can offer.
	 */
	companion object {
		/** The versions [enabledProtocols] may list, and the ones it holds when `server.yml` leaves it out. */
		val SUPPORTED_PROTOCOLS: List<String> = listOf("TLSv1.3", "TLSv1.2")
	}
}
