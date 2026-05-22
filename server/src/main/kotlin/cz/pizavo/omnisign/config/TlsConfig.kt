package cz.pizavo.omnisign.config

/**
 * TLS keystore configuration.
 *
 * When present and reverse-proxy mode is inactive ([ServerConfig.proxy] absent or
 * `proxy.enabled: false`), the server creates a TLS connector with TLS 1.2/1.3 and HTTP/2
 * ALPN negotiation. To restrict to TLS 1.3 only, pass
 * `-Djdk.tls.disabledAlgorithms=TLSv1,TLSv1.1,TLSv1.2` as a JVM argument.
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
 */
data class TlsConfig(
	val keystorePath: String,
	val keyAlias: String = "omnisign",
	val port: Int = 18443,
	val hsts: HstsConfig? = null,
)

