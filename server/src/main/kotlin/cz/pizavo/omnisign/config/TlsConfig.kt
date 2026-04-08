package cz.pizavo.omnisign.config

/**
 * TLS keystore configuration.
 *
 * When present and [ServerConfig.proxyMode] is `false`, the server creates a TLS connector
 * enforcing TLS v1.3+ with HTTP/2 ALPN negotiation.
 *
 * @property keystorePath Absolute path to the JKS or PKCS#12 keystore file.
 * @property keystorePassword Password protecting the keystore.
 * @property keyAlias Alias of the private key entry inside the keystore.
 * @property privateKeyPassword Password for the private key entry; defaults to [keystorePassword].
 */
data class TlsConfig(
	val keystorePath: String,
	val keystorePassword: String,
	val keyAlias: String = "omnisign",
	val privateKeyPassword: String = keystorePassword,
)

